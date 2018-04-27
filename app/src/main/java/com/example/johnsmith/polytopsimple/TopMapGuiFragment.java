package com.example.johnsmith.polytopsimple;

import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import processing.core.PApplet;
import processing.core.PImage;

public class TopMapGuiFragment extends PApplet {
    /**
     * PolyTop GUI. Node tree implemented in Processing.
     * Based on http://processingjs.nihongoresources.com/graphs
     */

    // Debug constants
    public static final String FRAG_TAG = "PolyTop"; // LogCat tag for this app
    public static final int FRAG_NEW = 0; // fragment state constants
    public static final int FRAG_STOPPED = 1;

    // Color constants
    public static String COLOR_MAT_GRAY_800 = "#424242";
    public static String COLOR_MAT_GRAY_600 = "#757575";
    public static String COLOR_MAT_GRAY_900 = "#212121";

    public static String COLOR_MAT_GREEN_300 = "#81C784";


    int mFragmentState = FRAG_NEW; // used to determine if fragment is restored (has state)

    // global access variables
    MainActivity mActivity;

    // data structures
    ConcurrentHashMap<Integer, Node> mNodes = new ConcurrentHashMap<>(); // since reading mNodes in draw() happens in parallel to adding them here, concurrency errors will happen. Thus we use ConcurrentHashMap and synchronize reading in draw().
    int mNodeCtr = -1; // sets unique node ID. Set to max nodeID coming from sync

    HashSet<Integer> mActiveFingers = new HashSet<>(10); // keeps track which idx are used in multi finger gestures. used in MODE_PAN

    HashMap<Integer, Node> mActiveNodes = new HashMap<>(10); // holds parent Nodes that are currently played in multitouch gesture
    HashMap<Integer, Node> mPreviousNodes = new HashMap<>(10); // holds parent Nodes that were previously played in a multitouch gesture
    HashMap<Integer, Thread> mNodePlayers = new HashMap<>(10); // holds periodic players of parent nodes
    HashMap<Integer, int[]> mNodePointerCoords = new HashMap<>(10); // holds pointerID - [x,y] coordinate pairs
    HashMap<Integer, Node> mAddedNodes = new HashMap<>(10); // holds child Nodes that are currently being added in multitouch gesture
    HashMap<Integer, Integer> mGestArmedPointers = new HashMap<>(10); // holds gesture recording status for each pointer

    ArrayList<Node> mAddedNodeHistory = new ArrayList<>(10);
    HashMap<Integer, Node> mCombTargets = new HashMap<>();

    ArrayList<DTPlayer> mDTPs = new ArrayList<>(Arrays.asList(new DTPlayer[]{null, null, null, null, null, null, null, null, null, null}));

    // movement global
    // pan
    int[] mLastPanPos = {0, 0}; // 1st finger position of previous motion event.

    // zoom
    int mLastPinchSize; // distance between 1st and 2nd finger of previous motion event.

    // DTPlayer tracking
    HashMap<Integer, int[]> mLastCanvasPos = new HashMap<>(10); // holds pointerID - [x,y] coordinate pairs

    // GUI globals
    int mBackgroundColor;
    PImage mRadiusImage;
    int mPhiQuant;

            /* Touch handling */
    ////////////////////

    View.OnTouchListener mTouchListener = new View.OnTouchListener() {

        @Override
        public boolean onTouch(View v, MotionEvent event) {

            // 1. get active finger index, ID // TODO: comment why Idx vs ID
            int pointerIdx = event.getActionIndex();
            int pointerId = event.getPointerId(pointerIdx); // TODO: make sure this is used everywhere

            // 2. get touch-surface coordinates of active finger // TODO: comment abs vs canvas coords
            int touchX = (int) event.getX(pointerIdx);
            int touchY = (int) event.getY(pointerIdx);

            // 3. adjust coordinates to pan position
            int canvasX = getCanvasX(touchX);
            int canvasY = getCanvasY(touchY);

            // 4. event action
            int eventAction = event.getActionMasked();

            Node activeNode;

            switch (mActivity.mHandMode) {
                case MainActivity.MODE_GEN:
                    switch (eventAction) {
                        case MotionEvent.ACTION_DOWN:
                        case MotionEvent.ACTION_POINTER_DOWN:
                            //                            mActivity.mDrawer.setDrawerLockMode(mActivity.mDrawer.LOCK_MODE_LOCKED_CLOSED); // lock drawer // TODO: uncomment this to lock navDrawer during touch gesture

                            activeNode = getNodeClicked(canvasX, canvasY);
                            if (activeNode == null) {
                                if (mPreviousNodes.get(pointerId) != null) {
                                    activeNode = mPreviousNodes.get(pointerId);
                                } else {
                                    activeNode = getOldestExistingNode(); // TODO: add this behaviour to documentation
                                }
                            }
                            mActiveNodes.put(pointerId, activeNode);

                            if (mActivity.mDrawActive) {
                                // to avoid editing data that we iterate, nodes are added to this batch and then transferred at the end of iteration
                                HashMap<Integer, Node> nodeBatch = new HashMap<>(); // nodes to be added after isClicked iteration finishes

                                Node addedNode = TopMapGuiFragment.this.addNodeToTempMap(canvasX, canvasY, activeNode, nodeBatch);
                                mAddedNodes.put(pointerId, addedNode);
                                mNodes.putAll(nodeBatch); // TODO: nodeBatch seems to be redundant after Touch Listener rewrite, remove
                            }

                            if (mActivity.mGestRecActive) {
                                mGestArmedPointers.put(pointerId, 1);
                            } else {
                                mGestArmedPointers.put(pointerId, 0);
                            }

                            int[] coords = {canvasX, canvasY};
                            mNodePointerCoords.put(pointerId, coords);

                            activeNode.style.radiVisible = true;

                            if (mActivity.mTimedMode == false) {
                                startDistanceTriggeredPlayer(pointerId, activeNode, mActivity.mVoiceType, mActivity.mHandMode);
                            } else {
                                startNodePlayer(pointerId, activeNode, mActivity.mVoiceType, mActivity.mHandMode);
                            }


                            break;

                        case MotionEvent.ACTION_MOVE:
                            for (int moveIdx = 0; moveIdx < event.getPointerCount(); moveIdx++) { // n.b. ACTION_MOVE has to cycle every pointer...
                                // recalculate canvas coords for this pointer
                                int touchXm = (int) event.getX(moveIdx);
                                int touchYm = (int) event.getY(moveIdx);

                                int canvasXm = getCanvasX(touchXm);
                                int canvasYm = getCanvasY(touchYm);

                                // get Id and coords for this (arbitrary) pointer
                                int pointerIdMoved = event.getPointerId(moveIdx); // n.b. this is different that the pointerID of onTouch
                                int[] coordsPtr = {canvasXm, canvasYm};

                                // track coords of this pointer
                                mNodePointerCoords.put(pointerIdMoved, coordsPtr);

                                if (mActivity.mDrawActive) {
                                    Node nodeDrawn = mAddedNodes.get(pointerIdMoved);
                                    if (nodeDrawn != null) { // check if moved Node is not null, since node may be initializing still
                                        nodeDrawn.setCoords(canvasXm, canvasYm);
                                    }
                                }

                                // check if pointing to new node, that is not part of addition gesture
                                Node newActiveNode = null;
                                if (!mActivity.mMeanderDisabled) {
                                    newActiveNode = getNodeClicked(canvasX, canvasY);
                                }
                                ;
                                if ((newActiveNode != null) && (newActiveNode != mActiveNodes.get(pointerId)) && (newActiveNode != mAddedNodes.get(pointerIdMoved)) && !mActivity.mDrawActive) {
                                    // 0. remove previous active node
                                    registerPointerReleaseGen(pointerId, canvasX, canvasY);

                                    // 1. set this node as active
                                    mActiveNodes.put(pointerId, newActiveNode);

                                    // 2. update node-pointer coordinates
                                    int[] newCoords = {canvasX, canvasY};
                                    mNodePointerCoords.put(pointerId, newCoords);

                                    // 3. start playing with right type
                                    if (mActivity.mTimedMode == true) {
                                        startNodePlayer(pointerId, newActiveNode, mActivity.mVoiceType, mActivity.mHandMode);
                                    } else {
                                        startDistanceTriggeredPlayer(pointerId, newActiveNode, mActivity.mVoiceType, mActivity.mHandMode);
                                    }
                                }

                                if (mActivity.mTimedMode == false) {
                                    // DTPlayer: calculate distance crossed from previous motion event
                                    int dX = canvasXm - mLastCanvasPos.get(pointerIdMoved)[0];
                                    int dY = canvasYm - mLastCanvasPos.get(pointerIdMoved)[1];
                                    mLastCanvasPos.get(pointerIdMoved)[0] = canvasXm;
                                    mLastCanvasPos.get(pointerIdMoved)[1] = canvasYm;

                                    double dDist = Math.sqrt(dX * dX + dY * dY);

                                    // check if corresponding DTPlayer needs to be triggered
                                    DTPlayer dtp = mDTPs.get(pointerIdMoved);
                                    if (dtp != null) {
                                        dtp.checkDistance(dDist);
                                    }
                                } else {
                                    // pass, in timed-trigger type Nodes are played in their own threads
                                }
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_POINTER_UP:
                            if (mActivity.mGestRecActive) {
                                mGestArmedPointers.put(pointerId, 2);
                            }
                            registerPointerReleaseGen(pointerId, canvasX, canvasY);

                            break;
                    }
                    break;

                case MainActivity.MODE_COMB:
                    switch (eventAction) {
                        case MotionEvent.ACTION_DOWN:
                        case MotionEvent.ACTION_POINTER_DOWN:

                            if (mActivity.mDrawActive) {
                                // create a new empty node
                                Node aNode = new Node(Integer.toString(mNodeCtr), mNodeCtr++, canvasX, canvasY);
                                mNodes.put(aNode.nodeID, aNode);

                                // 1. set node as active
                                mAddedNodes.put(pointerId, aNode);
                            }

                            if (mActivity.mGestRecActive) {
                                mGestArmedPointers.put(pointerId, 1);
                            } else {
                                mGestArmedPointers.put(pointerId, 0);
                            }

                            Node clickedNode = getNodeClicked(canvasX, canvasY);
                            mActiveNodes.put(pointerId, clickedNode); // in COMB_MODE, activeNodes are only used in selecting Node


                            // TODO: change GUI to indicate comb action in progress
                            //                    aNode.style.radiVisible = true; // display phi quant radii

                            // 2. update node-pointer coordinates
                            int[] coords = {canvasX, canvasY};
                            mNodePointerCoords.put(pointerId, coords);

                            if (mActivity.mTimedMode == false) {
                                //3. start distance-triggered counters
                                startDistanceTriggeredPlayer(pointerId, null, mActivity.mVoiceType, mActivity.mHandMode);
                            } else {
                                // 3. alternatively, start playing node at fixed intervals - temp debug sequence type
                                startNodePlayer(pointerId, null, mActivity.mVoiceType, MainActivity.MODE_COMB);
                            }
                            break;
                        case MotionEvent.ACTION_MOVE:
                            for (int moveIdx = 0; moveIdx < event.getPointerCount(); moveIdx++) { // n.b. ACTION_MOVE has to cycle every pointer...
                                // recalculate canvas coords for this pointer
                                int touchXm = (int) event.getX(moveIdx);
                                int touchYm = (int) event.getY(moveIdx);

                                int canvasXm = getCanvasX(touchXm);
                                int canvasYm = getCanvasY(touchYm);

                                // get Id and coords for this (arbitrary) pointer
                                int pointerIdMoved = event.getPointerId(moveIdx); // n.b. this is different that the pointerID of onTouch
                                int[] coordsPtr = {canvasXm, canvasYm};

                                // track coords of this pointer
                                mNodePointerCoords.put(pointerIdMoved, coordsPtr);

                                if (mActivity.mDrawActive) {
                                    Node nodeDrawn = mAddedNodes.get(pointerIdMoved);
                                    if (nodeDrawn != null) { // check if moved Node is not null, since node may be initializing still
                                        nodeDrawn.setCoords(canvasXm, canvasYm);
                                    }
                                }

                                if (mActivity.mTimedMode == false) {
                                    // DTPlayer: calculate distance crossed from previous motion event
                                    int dX = canvasXm - mLastCanvasPos.get(pointerIdMoved)[0];
                                    int dY = canvasYm - mLastCanvasPos.get(pointerIdMoved)[1];
                                    mLastCanvasPos.get(pointerIdMoved)[0] = canvasXm;
                                    mLastCanvasPos.get(pointerIdMoved)[1] = canvasYm;

                                    double dDist = Math.sqrt(dX * dX + dY * dY);

                                    // check if corresponding DTPlayer needs to be triggered
                                    DTPlayer dtp = mDTPs.get(pointerIdMoved);
                                    if (dtp != null) {
                                        dtp.checkDistance(dDist);
                                    }
                                } else {
                                    // pass, in timed-trigger type Nodes are played in their own threads
                                }
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_POINTER_UP:

                            // select Node, if POINTER_DOWN & POINTER_UP within same Node. Only if drawing is off.
                            if (!mActivity.mDrawActive) {
                                Node clickedNode2 = getNodeClicked(canvasX, canvasY);
                                Node releasedActiveNode = mActiveNodes.get(pointerId);
                                Log.i(FRAG_TAG, "mTouchListener: COMB_MODE : ACTION_POINTER_UP: clickedNode2 " + clickedNode2 + ", releasedActiveNode " + releasedActiveNode); // DEBUG
                                if ((clickedNode2 != null) && (releasedActiveNode != null) && (releasedActiveNode == clickedNode2)) {
                                    if (mCombTargets.get(clickedNode2.nodeID) != null) {
                                        mCombTargets.remove(clickedNode2.nodeID);
                                        clickedNode2.selected = false;
                                    } else {
                                        mCombTargets.put(clickedNode2.nodeID, clickedNode2);
                                        clickedNode2.selected = true;
                                    }
                                }
                            }

                            if (mActivity.mGestRecActive) {
                                mGestArmedPointers.put(pointerId, 2);
                            }
                            registerPointerReleaseComb(pointerId, canvasX, canvasY);
                            break;
                    }
                    break;
                case MainActivity.MODE_DEL:
                    Node selectedNode = getNodeClicked(canvasX, canvasY);
                    if (selectedNode != null) {
                        deleteNodeCompletely(selectedNode);
                    }

                    break;
                case MainActivity.MODE_PAN:
                    switch (eventAction) {
                        case MotionEvent.ACTION_DOWN:
                            mActiveFingers.add(pointerIdx);

                            // register initial pan-finger position
                            mLastPanPos[0] = touchX;
                            mLastPanPos[1] = touchY;
                            break;
                        case MotionEvent.ACTION_POINTER_DOWN:
                            // calculate initial pinch size
                            mLastPinchSize = calcPinchSize(event);

                            // correct for situation, when 1st finger is temporarily released (while 2nd finger is still down), then set back and user expects pan to continue
                            if (mActiveFingers.contains(1)) {
                                // if pointerIdx 1 is used, we are setting 1st finger back in pinch gesture
                                mLastPanPos[0] = (int) event.getX(0);
                                mLastPanPos[1] = (int) event.getY(0);
                            }
                            mActiveFingers.add(pointerIdx);
                            break;
                        case MotionEvent.ACTION_MOVE:
                            // n.b. works by calculating deltas between current and previous motion events
                        { // pan
                            // calc. 1st finger displacement from previous motion event
                            int dXPan = touchX - mLastPanPos[0];
                            int dYPan = touchY - mLastPanPos[1];

                            // TODO: explain why scaled translation is still in absolute units
                            mActivity.mPanPos[0] += dXPan;
                            mActivity.mPanPos[1] += dYPan;

                            mLastPanPos[0] = touchX;
                            mLastPanPos[1] = touchY;

                            // update pan coordinates in GUI
                            mActivity.mPanPosView.setText("[" + -1 * mActivity.mPanPos[0] + ", " + -1 * mActivity.mPanPos[1] + "] ");
                            mActivity.mZoomView.setText(String.format("%.2f", (mActivity.mScale)) + "x");
                        }

                        { // zoom
                            if (event.getPointerCount() > 1) { // if pinch is in progress...
                                // get midpoint between fingers
                                int centerPinchX = (int) ((event.getX(1) + event.getX(0)) / 2.0);
                                int centerPinchY = (int) ((event.getY(1) + event.getY(0)) / 2.0);
                                // Log.i("Midpoint", "P1(X,Y): (" + event.getX(0) + ", " + event.getY(0) + ");, P2(X,Y): (" + event.getX(1) + ", " + event.getY(1) + "); mid(X,Y): (" + centerPinchX + ", " + centerPinchY);

                                int pinchSize = calcPinchSize(event);
                                int dPinch = pinchSize - mLastPinchSize;

                                double dScale = scale(dPinch, -10, 10, 1 / 1.05, 1.05);

                                // as seen in https://stackoverflow.com/questions/20126028/how-to-zoom-in-to-the-point-under-the-mouse-cursor-processing
                                // ... answer 2
                                mActivity.mPanPos[0] -= centerPinchX;
                                mActivity.mPanPos[1] -= centerPinchY;

                                mActivity.mScale *= dScale;
                                mActivity.mPanPos[0] *= dScale;
                                mActivity.mPanPos[1] *= dScale;

                                mActivity.mPanPos[0] += centerPinchX;
                                mActivity.mPanPos[1] += centerPinchY;

                                mLastPinchSize = pinchSize;
                            }
                        }
                        break;
                        case MotionEvent.ACTION_UP:
                            mActiveFingers.remove(pointerIdx);
                            break;
                        case MotionEvent.ACTION_POINTER_UP:
                            mActiveFingers.remove(pointerIdx);

                            // correct for situation, when 1st finger is released and user wants to continue panning with 2nd finger
                            if (pointerIdx == 0) { // in case first finger released during pinch...
                                mLastPanPos = new int[]{(int) event.getX(1), (int) event.getY(1)}; // set last pan position to 2nd finger
                            }
                            break;
                        //                case MainActivity.MODE_WFS:
                        //                    break;
                    }
            }
            return true;
        }
    };

    Node getNodeClicked(int canvasX, int canvasY) {
            /* search for a node that was clicked
            @params: int canvasX, canvasY. Canvas coordinates of pointer
            @return: Node clickedNode. A node that was clicked. Null if no node at coords
            */
        Node clickedNode = null;
        single_click:
        for (TopMapGuiFragment.Node aNode : mNodes.values()) {
            if (aNode.isClicked(canvasX, canvasY)) {
                clickedNode = aNode;
                break single_click;
            }
        }
        return clickedNode;
    }

    Node getOldestExistingNode() {
            /* returns Node with lowest key - oldest existing node. Used for default Node selection, when re-triggering gesture outside of Node boundaries
            @return: Node node;
            // TODO: error checking;
             */
        Object[] keys = Collections.list(mNodes.keys()).toArray();
        Arrays.sort(keys);
        return mNodes.get(keys[0]);
    }

    public static double scale(final double valueIn, final double baseMin, final double baseMax, final double limitMin, final double limitMax) {
        /* linear scaling min/max input --> min/max output
        as seen on https://stackoverflow.com/questions/5294955/how-to-scale-down-a-range-of-numbers-with-a-known-min-and-max-value
        */
        return ((limitMax - limitMin) * (valueIn - baseMin) / (baseMax - baseMin)) + limitMin;
    }

    int calcPinchSize(MotionEvent event) {
        // calculate delta pinch finger distance
        int dX = (int) event.getX(0) - (int) event.getX(1);
        int dY = (int) event.getY(0) - (int) event.getY(1);
        int pinchSize = (int) Math.sqrt(dX * dX + dY * dY);
        return pinchSize;
    }

    int getCanvasX(int touchX) {
                /* maps touch surface to canvas surface, x coordinate
                */
        int canvasX = (int) ((touchX - mActivity.mPanPos[0]) / mActivity.mScale);
        return canvasX; // implement zoom adjustment
    }

    int getCanvasY(int touchY) {
                /* maps touch surface to canvas surface, y coordinate
                */
        int canvasY = (int) ((touchY - mActivity.mPanPos[1]) / mActivity.mScale);
        return canvasY; // implement zoom adjustment
    }

    //    void registerNodeClick(int pointerID, int x, int y) {
    //            /* checks if a new origin has been clicked and performs a gesture on that origin (play, add, comb), called on touchdown
    //            */
    //
    //        // n.b. we need to iterate over mNodes and add to it here. ConcurrentHashMap will not throw exception, but if the added values will get into the iterator is undefined.
    //        // thus we create a temp value set
    //        HashMap<Integer, Node> nodeBatch = new HashMap<>(); // nodes to be added after isClicked iteration finishes
    //
    //        single_click:
    //        // an exist to register only one click
    //        for (TopMapGuiFragment.Node aNode : mNodes.values()) {
    //            if (aNode.isClicked(x, y)) {
    //                if (mActivity.mCombineActive == false) {
    //                    switch (mActivity.mHandMode) {
    //                        case MainActivity.MODE_WFS:
    //                            mActivity.sendTopWFSOSC(aNode);
    //                            break;
    //                        case MainActivity.MODE_DRAW:
    //                            Node addedNode = TopMapGuiFragment.this.addNodeToTempMap(x, y, aNode, nodeBatch);
    //                            mAddedNodes.put(pointerID, addedNode);
    //
    //                            // continue, because MODE_DRAW includes MODE_PLAY playback...
    //                    }
    //                } else if (mActivity.mHandMode == MainActivity.MODE_DEL) { // if delete type...
    //                    // 1. delete arrows pointing to removed node from parent outNodes
    //                    for (Node parent : aNode.inNodes) {
    //                        int idxChild = parent.outNodes.indexOf(aNode);
    //                        parent.outNodes.remove(idxChild);
    //                    }
    //                    // 2. remove Node from GUI
    //                    mNodes.remove(aNode.nodeID);
    //
    //                    // 3. remove Node in server
    //                    mActivity.sendTopDeleteOSC(aNode.nodeID);
    //                }
    //                break single_click;
    //            }
    //        }
    //
    //        if (mActivity.mCombineActive == true) {
    //            // create a new empty node
    //            Node aNode = new Node(Integer.toString(mNodeCtr), mNodeCtr++, x, y);
    //
    //            switch (mActivity.mHandMode) {
    //                case MainActivity.MODE_DRAW:
    //                    nodeBatch.put(aNode.nodeID, aNode);
    //                    mAddedNodes.put(pointerID, aNode);
    //
    //                    // continue, because MODE_DRAW includes MODE_PLAY playback...
    //                case MainActivity.MODE_PLAY:
    //                    // 1. set node as active
    //                    mActiveNodes.put(pointerID, aNode);
    //
    //                    // TODO: change GUI to indicate comb action in progress
    ////                    aNode.style.radiVisible = true; // display phi quant radii
    //
    //                    // 2. update node-pointer coordinates
    //                    int[] coords = {x, y};
    //                    mNodePointerCoords.put(pointerID, coords);
    //
    //                    if (mActivity.mTimedMode == false) {
    //                        //3. start distance-triggered counters
    //                        startDistanceTriggeredPlayer(pointerID, aNode, mActivity.mHandMode);
    //                    } else {
    //                        // 3. alternatively, start playing node at fixed intervals - temp debug sequence type
    //                        startNodePlayer(pointerID, aNode, 1);
    //                    }
    //                    break;
    //            }
    //        }
    //        mNodes.putAll(nodeBatch); // add all Nodes to mNodes after iteration finishes
    //    }
    //
    //    void registerNodeClickMeander(int pointerID, int x, int y) {
    //            /* scanner for a node click event, called when a node is touched in a meander type
    //            */
    //
    //        // n.b. we need to iterate over mNodes and add to it here. ConcurrentHashMap will not throw exception, but if the added values will get into the iterator is undefined.
    //        // thus we create a temp value set
    //        HashMap<Integer, Node> nodeBatch = new HashMap<>(); // nodes to be added after isClicked iteration finishes
    //
    //        single_click:
    //        // an exit to register only one click
    //
    //        if (mActivity.mCombineActive == false) {
    //
    //            for (TopMapGuiFragment.Node aNode : mNodes.values()) {
    //
    //                // scan for click and prevent re-clicking same node for this pointer
    //                if ((aNode.isClicked(x, y)) && (mActiveNodes.get(pointerID) != aNode)) {
    //                    switch (mActivity.mHandMode) {
    //                        case MainActivity.MODE_PLAY:
    //                            // 0. remove previous active node
    //                            registerNodeReleaseMeander(pointerID, x, y);
    //
    //                            // 1. set node as active
    //                            mActiveNodes.put(pointerID, aNode);
    //
    //                            // 2. update node-pointer coordinates
    //                            int[] coords = {x, y};
    //                            mNodePointerCoords.put(pointerID, coords);
    //
    //                            if (mActivity.mTimedMode == false) {
    //                                //3. start distance-triggered counters
    //                                startDistanceTriggeredPlayer(pointerID, aNode, mActivity.mHandMode);
    //                            } else {
    //                                // 3. alternatively, start playing node at fixed intervals - temp debug sequence type
    //                                startNodePlayer(pointerID, aNode, 1);
    //                            }
    //
    //                            break;
    //                        case MainActivity.MODE_DEL: // if delete type...
    //                            deleteNodeCompletely(aNode);
    //                            break;
    //                    }
    //                    break single_click;
    //                }
    //            }
    //        } else {
    //            /* meandering in comb type is not a valid operation atm. TODO: Could be used to select another origin (synthdef) */
    //        }
    //        mNodes.putAll(nodeBatch); // add all Nodes to mNodes after iteration finishes
    //    }

    void deleteNodeCompletely(Node aNode) {
                /* deletes a node in client, reference arrows to it and node in server */
        // 1. delete arrows pointing to removed node from parent outNodes
        for (Node parent : aNode.inNodes) {
            if (parent != null) { // TODO: parent should never be null, that list item should be deleted. This is a temp fix.
                int idxChild = parent.outNodes.indexOf(aNode);
                parent.outNodes.remove(idxChild);
            }
        }
        // 2. remove Node from GUI
        mNodes.remove(aNode.nodeID);

        // 3. remove Node in server
        mActivity.sendTopDeleteOSC(aNode.nodeID);

        // 4. remove Node from undo list
        mAddedNodeHistory.remove(aNode);

        // 5. remove Node from comb selection, if selected
        mCombTargets.remove(aNode.nodeID); // remove added node from combTargets
    }


    void registerPointerReleaseGen(int pointerId, int x, int y) {
        /* for MODE_GEN, removes drawn/played node from active nodes (mActiveNodes), ends player (periodic or DTPlayer), and creates a new nodes in draw mode.  */

        // 1. set node inactive
        Node nodeReleased = mActiveNodes.remove(pointerId);
        mPreviousNodes.put(pointerId, nodeReleased); // ... used in re-triggering without node click

        if (nodeReleased != null) {
            nodeReleased.style.radiVisible = false; // hide phiQuant radii

            // 2. stop playing node
            if (mActivity.mTimedMode == true) {
                mNodePlayers.get(pointerId).interrupt();

                int recGest = mActivity.mGestRecActive ? 2 : 0;
                mActivity.sendTopPlayOSC(nodeReleased, x, y, pointerId, mActivity.mVoiceType, 0, recGest); // release node, play type

            } else {
                DTPlayer dtp = mDTPs.get(pointerId);
                dtp.releaseVoice();
                mDTPs.set(pointerId, null);
            }

            // 3. in draw-type, add node
            if (((mActivity.mHandMode == MainActivity.MODE_GEN) || (mActivity.mHandMode == MainActivity.MODE_COMB)) && (mActivity.mDrawActive)) {
                // TODO: retrieve node added at the beginning of this gesture
                Node nodeAdded = mAddedNodes.remove(pointerId); // child node that is being positioned
                mActivity.sendTopAddOSC(nodeReleased, nodeAdded, x, y);
                mAddedNodeHistory.add(nodeAdded); // for undo function
            }
        }
    }

    void registerPointerReleaseComb(int pointerId, int x, int y) {
        /* for MODE_COMB, ends player (periodic or DTPlayer), and creates a new nodes in draw type. */

        // 2. stop playing node
        if (mActivity.mTimedMode == true) {
            mNodePlayers.get(pointerId).interrupt();
            int recGest = mActivity.mGestRecActive ? 2 : 0;
            mActivity.sendTopCombineOSC(x, y, pointerId, mActivity.mVoiceType, 0, recGest);
        } else {
            DTPlayer dtp = mDTPs.get(pointerId);
            dtp.releaseVoiceCombine();
            mDTPs.set(pointerId, null);
        }

        // 3. in draw-type, add node
        if (((mActivity.mHandMode == MainActivity.MODE_GEN) || (mActivity.mHandMode == MainActivity.MODE_COMB)) && (mActivity.mDrawActive)) {
            // TODO: retrieve node added at the beginning of this gesture
            Node nodeAdded = mAddedNodes.remove(pointerId); // child node that is being positioned
            mActivity.sendTopAddOSC(null, nodeAdded, x, y);
            mAddedNodeHistory.add(nodeAdded); // for undo function
        }
    }

    //
//    if (mActivity.mHandMode == MainActivity.MODE_COMB) {
//        mActivity.sendTopCombineOSC(x, y, pointerId, mActivity.mVoiceType, 0); // release node, comb type
//    } else {
//
//    }
//     if (mActivity.mHandMode != MainActivity.MODE_COMB) {
//        dtp.releaseVoice();
//    } else {


    public Node addNodeToTempMap(int x, int y, Node origin, HashMap<Integer, Node> tempMap) {
                /* acts like add Node, but instead creates a temp map, that can be transferred to mNodes AFTER iteration on it
                (used to solve ConcurrentHashMap iterate and add issue)
                @params: int x, int y - coordinates; Node origin node to be added; HashMap tempMap to store all node from single iteration.
                n.b. this modifies tempMap that will be added to current map as well as returns the new node
                 */
        // make and setup node
        Node node = new Node(null, -1, x, y);
        origin.addOutgoingNode(node);
        node.addIncomingNode(origin);
        node.phiQuant = mPhiQuant;

        tempMap.put(node.nodeID, node);
        return node;
    }

    void startDistanceTriggeredPlayer(int pointerID, Node node, int voiceMode, int handMode) {
                /* Creates and arms DTPlayer
                @params:
                Node node - node that is read from once fixed distance is travelled. Null in comb mode
                */
        if (mDTPs.get(pointerID) == null) {
            // make new DTPlayer
            DTPlayer dtp;
            if (handMode == MainActivity.MODE_GEN) {
                dtp = new DTPlayer(pointerID, node, voiceMode);
                mDTPs.set(pointerID, dtp);
                dtp.playVoice();
            } else if (handMode == MainActivity.MODE_COMB) {
                dtp = new DTPlayer(pointerID, voiceMode);
                mDTPs.set(pointerID, dtp);
                dtp.playVoiceCombine();
            }
        } else {
            Log.e(FRAG_TAG, "startDistanceTriggeredPlayer(): WARNING, DTPlayer overwritten - perhaps previous DTPlayer not released correctly due to missed ACTION_UP?");
        }

    }

    void startNodePlayer(final int pointerId, final Node node, final int voiceType, final int handMode) {
                /* player that sends periodic updates (i.e. every 500ms) to server to play selected node
                @params: Node node. Node that is read from once fixed time interval passes. Null in comb mode
                */
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                //                Log.e(FRAG_TAG, "startNodePlayer(): with pointerId " + pointerId); // DEBUG
                //                Log.e(FRAG_TAG, "startNodePlayer(): mNodes: " + mNodes); // DEBUG

                try {
                    while (true) {
                        //                        Log.e(FRAG_TAG, "startNodePlayer(): PING pointerId " + pointerId); // DEBUG
                        int x = mNodePointerCoords.get(pointerId)[0];
                        int y = mNodePointerCoords.get(pointerId)[1];
                        if (handMode == MainActivity.MODE_GEN) {
                            int gestRec = mActivity.mGestRecActive ? 1 : 0;
                            mActivity.sendTopPlayOSC(node, x, y, pointerId, voiceType, 1, gestRec); // play node
                        } else if (handMode == MainActivity.MODE_COMB) {
                            int recGest = mActivity.mGestRecActive ? 1 : 0;
                            mActivity.sendTopCombineOSC(x, y, pointerId, voiceType, 1, recGest);
                        }
                        Thread.sleep(mActivity.mPlayerUpdatePeriod);
                    }
                } catch (InterruptedException e) {
                    // do nothing
                }
            }
        });
        thread.start();
        mNodePlayers.put(pointerId, thread);
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {

        View rootView = super.onCreateView(inflater, container, savedInstanceState);

        // set multitouch listener that plays, draws, deletes or combines nodes
        rootView.setOnTouchListener(mTouchListener);
        mActivity.mPanPos = new int[]{width / 4, height / 2};

        // initialize mLastCanvasPos with {0,0} for all pointers
        for (int idx = 0; idx < 10; idx++) {

            mLastCanvasPos.put(idx, new int[]{0, 0});
        }
        return rootView;
    }

    @Override
    public void onStop() {
        super.onStop();
        mFragmentState = FRAG_STOPPED;
        //        Log.i(FRAG_TAG, "TopMapGui.onStop() called"); // DEBUG
    }

    // Processing Init Methods //
    /////////////////////////////

    @Override
    public void settings() {
                /* initializer of the Processing sketch. Called after onCreateView of fragment (each time fragment is reattached) */
        // 1. Parse sync and create array of TopMapSync objects
        if (mFragmentState == FRAG_NEW) {
            // if first initialization
            Gson gson = new Gson();
            MainActivity.TopMapSync[] topMapSyncs = gson.fromJson(getArguments().getString("rawSyncData"), MainActivity.TopMapSync[].class); // TODO: avoid reading this, it causes concurrency (?) crash

            // TODO: move this to global sync
            // Use origin node to read in some GUI globals
            if (topMapSyncs[0] != null) {
                mPhiQuant = topMapSyncs[0].phiQuant;
            } else {
                Log.e(FRAG_TAG, "TopManGuiFragment.settings(): tried to read phiQuant from first node. Node not found!");
            }

            initSyncedNodes(topMapSyncs);
        } else {
            // if reattached, or returning from other app, do not rebuild
            //            Log.i(FRAG_TAG, "TopMapGui.settings(): fragment is not new, initSyncedNodes NOT called"); // DEBUG
        }

        mActivity = (MainActivity) getActivity();

        //Gui
        //        fullScreen(P3D); // n.b. P3D on android does not support antialiasing. Also in P3D type frameRate() is ignored
        //        fullScreen(); // TODO: solve problem with Node fill.
    }

    private void initSyncedNodes(MainActivity.TopMapSync[] topMapSyncs) {
                /* initializes Nodes from array of topMapSync nodes */
        // TODO: this may be causing problems when I re-sync the fragment which already exists, but has not been stopped before. I.e. adding repeated links
        // 0. find highest node count
        for (MainActivity.TopMapSync topMapSync : topMapSyncs) {
            if (topMapSync.originID > mNodeCtr) {
                mNodeCtr = topMapSync.originID;
            }
        }
        mNodeCtr += 1; // start counting from one above

        // 1. initialize all nodes

        for (MainActivity.TopMapSync topMapSync : topMapSyncs) {
            int id = topMapSync.originID;
            int x = topMapSync.absCoords[0];
            int y = topMapSync.absCoords[1];
            String linkName = topMapSync.linkName;

            Node node = new Node(Integer.toString(id), id, x, y);
            node.linkName = linkName;
            mNodes.put(node.nodeID, node);
            node.setPhiQuant(topMapSync.phiQuant);
        }

        // 2. for each node
        for (MainActivity.TopMapSync topMapSync : topMapSyncs) {
            int id = topMapSync.originID;

            // 2.1. add all incoming nodes
            for (int inlinkID : topMapSync.inLinks) {
                Node child = mNodes.get(id);
                Node parent = mNodes.get(inlinkID);
                child.inNodes.add(parent);
            }

            // 2.2. add all outgoing nodes
            for (int outlinkID : topMapSync.outLinks) {
                Node parent = mNodes.get(id);
                Node child = mNodes.get(outlinkID);
                parent.outNodes.add(child);
            }
        }
    }

    public void setup() {
        mBackgroundColor = Color.parseColor(COLOR_MAT_GRAY_800);
        mRadiusImage = loadImage("radius_blur_v0.1.png");
        frameRate(24);
    }

    public void draw() {
        imageMode(CENTER); // n.b. this would not be required but due to a bug (?) in Processing, once fragment is reinitialized, imageMode is lost. Yet you can't call setup() or settings() in any of the callbacks
        background(mBackgroundColor);

        pushMatrix();
        // center camera at pinch, scale, then center camera back at global pan
        translate(mActivity.mPanPos[0], mActivity.mPanPos[1]);
        scale((float) mActivity.mScale);

        // draw Nodes
        synchronized (mNodes) {
            for (Node n : mNodes.values()) {
                n.draw();
            }
        }
        popMatrix();
    }

            /* Node class */
////////////////

class Node {
    List<Node> inNodes = Collections.synchronizedList(new ArrayList<Node>());
    List<Node> outNodes = Collections.synchronizedList(new ArrayList<Node>());


    String name;
    int nodeID = 0; // n.b. this is max limit of nodes. This has to be unique
    String linkName;
    int phiQuant;
    boolean selected = false;

    Properties style;

    Node(String _name, int id, int _x, int _y) {
        // Give node a unique ID
        if (id == -1) { // ID is -1 to get auto ID
            nodeID = mNodeCtr;
            mNodeCtr++;
        } else {
            nodeID = id;
        }

        if (_name == null) {
            name = Integer.toString(nodeID);
        } else {
            name = _name;
        }
        x = _x;
        y = _y;
        style = new Properties();

        if (mActivity != null) { // if activity has been initialized
            this.linkName = mActivity.mGenAlg;
        } else {
            this.linkName = "nil"; // keep SC null for Node before activity was initialized.
        }
    }

    void setPhiQuant(int phiQuant) {
        this.phiQuant = phiQuant;
    }

    void setCoords(int x, int y) {
        this.x = x;
        this.y = y;
    }

    void addIncomingNode(Node n) {
        if (!inNodes.contains(n)) {
            inNodes.add(n);
        }
    }
    //        Unused code
    //        List<Node> getIncomingNodes() {
    //            return inNodes;
    //        }
    //
    //        int getIncomingNodesCount() {
    //            return inNodes.size();
    //        }

    void addOutgoingNode(Node n) {
        if (!outNodes.contains(n)) {
            outNodes.add(n);
        }
    }

    //        Unused code
    //        <dummy line>
    //        List<Node> getOutgoingLinks() {
    //            return outNodes;
    //        }
    //
    //        int getOutgoingNodesCount() {
    //            return outNodes.size();
    //        }
    //
    //        float getShortestLinkLength() {
    //            if (inNodes.size() == 0 && outNodes.size() == 0) {
    //                return -1;
    //            }
    //            float l = 100;
    //            for (Node inode : inNodes) {
    //                int dx = inode.x - x;
    //                int dy = inode.y - y;
    //                float il = sqrt(dx * dx + dy * dy);
    //                if (il < l) {
    //                    l = il;
    //                }
    //            }
    //            for (Node onode : outNodes) {
    //                int dx = onode.x - x;
    //                int dy = onode.y - y;
    //                float ol = sqrt(dx * dx + dy * dy);
    //                if (ol < l) {
    //                    l = ol;
    //                }
    //            }
    //            return l;
    //        }
    //
    //        boolean equals(Node other) {
    //            if (this == other) return true;
    //            return name.equals(other.name);
    //        }

    boolean isClicked(float x, float y) {
        // assumes r1 == r2
        double distance = Math.sqrt(Math.pow(x - this.x, 2) + Math.pow(y - this.y, 2));
        //            Log.i(FRAG_TAG, "isClicked(): distance " + distance);
        if (distance < r1 * this.style.size) {
            return true;
        } else {
            return false;
        }
    }

                /* Visualisation-Specific code */
    /////////////////////////////////

    class Properties {
        /* properties of a node and its arrows */
        // node
        int nodeLabelSize = 24;
        int nodeLabelColor = Color.LTGRAY;
        int nodeFillColor = Color.parseColor(COLOR_MAT_GRAY_900);
        //            int nodeLineColor = Color.parseColor(COLOR_MAT_GRAY_600);
        int nodeLineColor = Color.parseColor(COLOR_MAT_GRAY_600);
        int nodeLineWeight = 8;

        // link
        int arrowLabelSize = 20;
        //            int centerColor = 0xFF0000; // default red // DEBUG
        int arrowLineColor = ContextCompat.getColor(getContext(), R.color.electric);
        int arrowFillColor = ContextCompat.getColor(getContext(), R.color.electric_dark);
        float size = 11.2f; // radius multiplier. Should be same for all nodes, because hitbox will depend on it.
        int arrowLabelColor = Color.LTGRAY;

        // radii
        int radiSize = 750; // size of the radial symmetry phiQuant divisor
        int radiColor = Color.parseColor(COLOR_MAT_GREEN_300);
        boolean radiVisible = false;

        // selection circle
        int dashWidth = 10;
        int dashSpacing = 4;

        int centerColor = Color.RED;

        Properties() {
            /** empty constructor  */
        }
    }

    int x = 0; // x-coordinate of node
    int y = 0; // y-coordinate of node
    int r1 = 5; // horizontal radius
    int r2 = 5; // vertical radius

    //        void setPosition(int _x, int _y) {
    //            x = _x;
    //            y = _y;
    //        }
    //
    //        void setRadii(int _r1, int _r2) {
    //    /* sets circles size */
    //            r1 = _r1;
    //            r2 = _r2;
    //        }

    void draw() {

        // draw extra elements
        // phiQuant radii
        pushMatrix();

        if ((mActivity.mDrawPhiQuantRadii == true) && (style.radiVisible == true)) {
            float angle = (float) Math.PI / this.phiQuant;
            stroke(style.radiColor);
            for (int i = 0; i < this.phiQuant; i++) {
                translate(x, y);
                image(mRadiusImage, 0, 0, style.radiSize, 15);
                //                    line(0 - style.radiSize, 0, 0 + style.radiSize, 0); // alternatively draw a line
                rotate(angle);
                translate(-x, -y);
            }
        }
        popMatrix();

        // draw nodes
        stroke(style.nodeLineColor);
        fill(style.nodeFillColor);
        strokeWeight(style.nodeLineWeight);
        ellipse(x, y, r1 * 2 * style.size, r2 * 2 * style.size);
        strokeWeight(1);
        //            noFill();
        noStroke();

        stroke(Color.YELLOW);
        if (selected) {
            drawDashedCircle(x, y, r1 * style.size * 1.1f, style.dashWidth, style.dashSpacing); // 20% bigger than the circle
        }
        noStroke();

        // draw links
        synchronized (outNodes) {
            for (Node o : outNodes) {
                // draw link up to the boundary of Node, vector calculations // TODO vector calculation is wrong
                // vF = vB - vE, where vE is radius vector with an angle pointing to parent Node

                // vN = vB - vA
                int xVn = o.x - x;
                int yVn = o.y - y;

                double thetaN;
                if (xVn != 0) {
                    thetaN = Math.atan(1.0 * yVn / xVn);
                } else {
                    thetaN = Math.PI / 2;
                }
                if (((xVn < 0) && (yVn < 0)) || ((xVn < 0) && (yVn > 0))) { // aTan quadrant correction
                    thetaN = thetaN + Math.PI;
                }

                // vE - radius vector.
                // vE angle can be calculated from vN
                double thetaE = thetaN - Math.PI;
                int xVe = (int) ((style.size * r1 + (style.size * 9 / 2)) * Math.cos(thetaE));
                int yVe = (int) ((style.size * r1 + (style.size * 9 / 2)) * Math.sin(thetaE));

                // vF - vector pointing to boundary of Node.
                int xVf = o.x + xVe;
                int yVf = o.y + yVe;

                // check if Nodes overlap..
                double nodeDistance = Math.sqrt(Math.pow(xVn, 2) + Math.pow(yVn, 2));

                if (nodeDistance < (r1 * style.size * 2)) { // draw to center
                    drawArrow(x, y, o.x, o.y, o.linkName, false); // draw to boundary
                } else {
                    drawArrow(x, y, xVf, yVf, o.linkName, true); // draw to boundary
                }
            }
        }

        // draw text
        fill(style.nodeLabelColor);
        textSize(style.nodeLabelSize);
        text(name, x + (r1 * style.size), y + (r2 * style.size));
        noFill();
        //            stroke(style.centerColor); // mark red center // DEBUG
        //            point(x, y);
        //
        //            stroke(style.centerColor); // mark red center
        //            point(x + (r1 * style.size), y + (r2 * style.size));
    }

    int[] arrowhead = {0, -3, 0, 3, 7, 0};

    void drawArrow(int x, int y, int ox, int oy, String arrowLabel, boolean arrowHeadVisible) {
        int dx = ox - x;
        int dy = oy - y;
        float angle = getDirection(dx, dy);
        float vl = sqrt(dx * dx + dy * dy) - sqrt(r1 * r1 + r2 * r2);
        int[] end = rotateCoordinate(vl, 0, angle);
        stroke(style.arrowLineColor);
        line(x, y, x + end[0], y + end[1]);
        noStroke();

        if (arrowHeadVisible) {
            drawArrowHead(x + end[0], y + end[1], angle);
        }
        drawArrowLabel(x + (end[0] / 2), y + (end[1] / 2), arrowLabel);
    }

    void drawArrowHead(int ox, int oy, float angle) {
        int[] rc1 = rotateCoordinate(arrowhead[0] * style.size / 2, arrowhead[1] * style.size / 2, angle);
        int[] rc2 = rotateCoordinate(arrowhead[2] * style.size / 2, arrowhead[3] * style.size / 2, angle);
        int[] rc3 = rotateCoordinate((float) (arrowhead[4] * style.size / 1.5), (float) (arrowhead[5] * style.size / 1.5), angle);
        int[] narrow = {ox + rc1[0], oy + rc1[1], ox + rc2[0], oy + rc2[1], ox + rc3[0], oy + rc3[1]};
        stroke(style.arrowLineColor);
        fill(style.arrowFillColor);

        beginShape();
        vertex(narrow[0], narrow[1]);
        vertex(narrow[2], narrow[3]);
        vertex(narrow[4], narrow[5]);
        endShape(CLOSE);

        //            triangle(narrow[0], narrow[1], narrow[2], narrow[3], narrow[4], narrow[5]);
        //            noFill();
        noStroke();
    }

    void drawArrowLabel(int x, int y, String label) {
                    /* draws label of link.
                    @params: int x,y of midpoint of arrow. String label.
                     */
        fill(style.arrowLabelColor);
        textSize(style.arrowLabelSize);
        text(label, x + style.arrowLabelSize, y + style.arrowLabelSize);
        noFill();
    }

    void drawDashedCircle(float x, float y, float radius, int dashWidth, int dashSpacing) {
        pushMatrix();
        translate(x, y);
        int steps = 200;
        int dashPeriod = dashWidth + dashSpacing;
        boolean lastDashed = false;
        for (int i = 0; i < steps; i++) {
            boolean curDashed = (i % dashPeriod) < dashWidth;
            if (curDashed && !lastDashed) {
                beginShape();
            }
            if (!curDashed && lastDashed) {
                endShape();
            }
            if (curDashed) {
                float theta = map(i, 0, steps, 0, TWO_PI);
                vertex(cos(theta) * radius, sin(theta) * radius);
            }
            lastDashed = curDashed;
        }
        if (lastDashed) {
            endShape();
        }
        translate(-x, -y);
        popMatrix();
    }
}

            /* Player classes */
////////////////////

class DTPlayer {
    /* DTPlayer - Distance Triggered Player. Holds the node (nodes in comb mode) that must be played once a finger travel certain distance
    - remembers voice type - cont. / discr. for the travel distance between nodes, even if it changed globally
     */
    int pointerID;
    Node node;
    int voiceMode;
    int threshold = -1; // treshold of travelling distance before this.playNode() is called, set according to type
    double distance; // total distance scrolled since creation

    DTPlayer(int pointerID, Node node, int voiceMode) {
        Log.i(FRAG_TAG, "DTPlayer.init(): created gen mode DTP for finger " + pointerID);
        this.pointerID = pointerID;
        this.node = node;
        this.voiceMode = voiceMode;

        if (voiceMode == MainActivity.TYPE_CONTINUOUS) {
            this.threshold = mActivity.mContinuousUpdateDistance;
        } else if (voiceMode == MainActivity.TYPE_DISCRETE) {
            this.threshold = mActivity.mDiscreteUpdateDistance; }
        else if (voiceMode == MainActivity.TYPE_BEHAVIOR) {
            this.threshold = mActivity.mBehaviorUpdateDistance;
        } else {
            Log.e(FRAG_TAG, "DTPlayer.new(): wrong voice type!");
        }
    }

    DTPlayer(int pointerID, int voiceMode) {
            /* constructor for combNodes */
        Log.i(FRAG_TAG, "DTPlayer.init(): created comb mode DTP for finger " + pointerID);
        this.pointerID = pointerID;
        this.voiceMode = voiceMode;

        if (voiceMode == MainActivity.TYPE_CONTINUOUS) {
            this.threshold = mActivity.mContinuousUpdateDistance;
        } else if (voiceMode == MainActivity.TYPE_DISCRETE) {
            this.threshold = mActivity.mDiscreteUpdateDistance;
        } else {
            Log.e(FRAG_TAG, "DTPlayer.new(): wrong voice type!");
        }
    }

    void playVoice() {
        int x = mNodePointerCoords.get(this.pointerID)[0];
        int y = mNodePointerCoords.get(this.pointerID)[1];
        int gestRec = mActivity.mGestRecActive ? 1 : 0;
        mActivity.sendTopPlayOSC(node, x, y, this.pointerID, this.voiceMode, 1, gestRec); // play node
    }

    void playVoiceCombine() {
        // TODO: deduplicate this code (use param instead)
                /* same as playVoice, only sends /topComb */
        int x = mNodePointerCoords.get(this.pointerID)[0];
        int y = mNodePointerCoords.get(this.pointerID)[1];
        int recGest = mActivity.mGestRecActive ? 1 : 0;
        mActivity.sendTopCombineOSC(x, y, this.pointerID, this.voiceMode, 1, recGest); // play node
    }

    void releaseVoice() {
        Thread releaseThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(mActivity.waitBeforeRelease);
                    int x = mNodePointerCoords.get(DTPlayer.this.pointerID)[0];
                    int y = mNodePointerCoords.get(DTPlayer.this.pointerID)[1];
                    int recGest = mActivity.mGestRecActive ? 2 : 0;
                    mActivity.sendTopPlayOSC(node, x, y, DTPlayer.this.pointerID, DTPlayer.this.voiceMode, 0, recGest); // release node
                } catch (InterruptedException e) {
                    // do nothing
                }
            }
        });
        releaseThread.run();

    }

    void releaseVoiceCombine() {
        // TODO: deduplicate this code (use param instead)
        Thread releaseThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(mActivity.waitBeforeRelease);
                    int x = mNodePointerCoords.get(DTPlayer.this.pointerID)[0];
                    int y = mNodePointerCoords.get(DTPlayer.this.pointerID)[1];
                    int recGest = mActivity.mGestRecActive ? 2 : 0;
                    mActivity.sendTopCombineOSC(x, y, DTPlayer.this.pointerID, DTPlayer.this.voiceMode, 0, recGest); // release node
                } catch (InterruptedException e) {
                    // do nothing
                }
            }
        });
        releaseThread.run();
    }

    void checkDistance(double dTravelled) {
                    /* called externally from Touch Event listener, to see if treshold is passed once dTravelled is added*/
        this.distance += dTravelled;
        if (this.distance > this.threshold) {
            if (mActivity.mHandMode == MainActivity.MODE_GEN) {
                this.playVoice();
            } else if (mActivity.mHandMode == MainActivity.MODE_COMB) {
                this.playVoiceCombine();
            } else {
                Log.e(FRAG_TAG, "checkDistance(): trigger failed, hand type error...");
            }
            this.distance = 0;
        }
    }

}

    //    class Link {
    //        /* Holds a child Node and the extra information how that Node was made (i.e. algorithm for linkLabel);
    //         */
    //        String linkLabel = null;
    //        Node child = null;
    //
    //        Link (String linkLabel, Node childNode) {
    //            this.linkLabel = linkLabel;
    //            this.child = childNode;
    //        }
    //    }

            /* Helper Functions */
    //////////////////////

    // universal helper function: get the angle (in radians) for a particular dx/dy
    float getDirection(double dx, double dy) {
        // quadrant offsets
        double d1 = 0.0;
        double d2 = PI / 2.0;
        double d3 = PI;
        double d4 = 3.0 * PI / 2.0;
        // compute angle basd on dx and dy values
        double angle = 0;
        float adx = abs((float) dx);
        float ady = abs((float) dy);
        // Vertical lines are one of two angles
        if (dx == 0) {
            angle = (dy >= 0 ? d2 : d4);
        }
        // Horizontal lines are also one of two angles
        else if (dy == 0) {
            angle = (dx >= 0 ? d1 : d3);
        }
        // The rest requires trigonometry (note: two use dx/dy and two use dy/dx!)
        else if (dx > 0 && dy > 0) {
            angle = d1 + atan(ady / adx);
        }        // direction: X+, Y+
        else if (dx < 0 && dy > 0) {
            angle = d2 + atan(adx / ady);
        }        // direction: X-, Y+
        else if (dx < 0 && dy < 0) {
            angle = d3 + atan(ady / adx);
        }        // direction: X-, Y-
        else if (dx > 0 && dy < 0) {
            angle = d4 + atan(adx / ady);
        }        // direction: X+, Y-
        // return directionality in positive radians
        return (float) (angle + 2 * PI) % (2 * PI);
    }

    // universal helper function: rotate a coordinate over (0,0) by [angle] radians
    int[] rotateCoordinate(float x, float y, float angle) {
        int[] rc = {0, 0};
        rc[0] = (int) (x * cos(angle) - y * sin(angle));
        rc[1] = (int) (x * sin(angle) + y * cos(angle));
        return rc;
    }

}
