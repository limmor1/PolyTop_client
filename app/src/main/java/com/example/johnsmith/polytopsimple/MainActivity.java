package com.example.johnsmith.polytopsimple;

import android.app.FragmentManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.NavigationView;
import android.support.design.widget.Snackbar;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.getbase.floatingactionbutton.FloatingActionButton;
import com.getbase.floatingactionbutton.FloatingActionsMenu;
import com.google.gson.Gson;
import com.illposed.osc.OSCListener;
import com.illposed.osc.OSCMessage;
import com.illposed.osc.OSCPortIn;
import com.illposed.osc.OSCPortOut;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;


public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    // Debugging constants
    public static final String TAG = "PolyTop"; // LogCat tag for this app

    // savedInstanceState constant names for global vars to retain on config change
    public static final String IS_SYNCED = "isSynced";
    public static final String SYNC_MESSAGE = "syncMessage";

    // OSC message codes
    // ... incoming
    public static final String OSC_SYNC = "/topSync";
    public static final String OSC_SYNC_PART = "/topSyncPart";
    public static final String OSC_SYNC_PART_END = "/topSyncPartEnd";
    public static final String OSC_TOP_ALGS_SYNC = "/topAlgsSync";
//    public static final String OSC_TOP_GLOBAL_SYNC = "/topGlobalSync";

    // ... outgoing
    public static final String OSC_PLAY = "/topPlay";
    public static final String OSC_COMB = "/topComb";
    public static final String OSC_SYNC_REPLY = "/topSyncReply";
    public static final String OSC_ADD = "/topAdd";
    public static final String OSC_DELETE = "/topDelete";
//    public static final String OSC_SET_GENALG = "/topSetGenAlg";
    public static final String OSC_SET_COMBALG = "/topSetCombAlg";
    public static final String OSC_WFS = "/topSendToWFS";


    // Network variables
    static String mServerIP;
    static final String SERVER_IP = "192.168.1.100";
    private static final int SERVER_PORT = 12395;
    private static final int CLIENT_PORT = 12396;

    private OSCPortIn mOscPortIn;
    private OSCPortOut mOscPortOut;

    // General globals
    private boolean isSynced = false; // has client received sync OSC message from Server
    private String syncMessage = null; // String in JSON dictionary format, contains data about map
    private String syncPartMessage = ""; // used when sync arrives in parts

    private FragmentManager mFragmentManager;
    public View mRootView; //root view for snackbar posting
    public DrawerLayout mDrawer;
    ArrayList<LinearLayout> mFabAlgButtons = null; // keep track of all alg buttons for easy removal
    ScrollView mAlgMenuScrollView;
    FloatingActionButton mAlgsMenuToggle;
    boolean mAlgsMenuOpen = false;
    boolean mAlgsMenuScrollViewDisabled = true;

    Gson mGson = new Gson();

    // Pan globals
    public TextView mPanPosView;
    public TextView mZoomView;
    public int[] mPanPos = {0, 0};
    public int[] absPinch = {0, 0}; // location of pinch finger 0 (for correct scaling)
    public double mScale = 1; // controls zoom of sketch
    public double mMinScale = 0.001; // minimal scale boundary for mScale

    // Mode related globals:
    public static final int MODE_GEN = 0;
    public static final int MODE_COMB = 1;
    //    public static final int MODE_DRAW = 1; // deprecated since v2.0
    public static final int MODE_DEL = 2;
    public static final int MODE_PAN = 3;
    public static final int MODE_WFS = 4;

    public static final int TYPE_DISCRETE = 0;
    public static final int TYPE_CONTINUOUS = 1; // todo: put back to 1
    public static final int TYPE_BEHAVIOR = 2;

    public static final int COMB_ACTIVE = 1; // signals server that Add is in comb type
    public static final int COMB_INACTIVE = 0; // v.v.

    int mHandMode = MODE_GEN; // current ACTION MODE, selected by FAB
    boolean mDrawActive = false; // draws nodes in MODE_GEN if active
    boolean mGestRecActive = false; // controls if pointer gestures are being recorded
    //    boolean mCombineActive = false; // if combAlg is selected, then we are in combMode
    int mVoiceType = TYPE_CONTINUOUS; // current VOICE MODE, selected by FAB

    String mGenAlg = "random walk (0.5%)"; // selected genAlg
    String mCombAlg = "interpolate (lin)"; // selected combAlg

    // debug-related settings
    boolean mTimedMode = false; // debug type, which uses periodic player instead of DTPlayer

    // TopGlobalSync settings
//    public TopGlobalSync mTopGlobalSync; TODO if global sync is necessary

    // other settings
    int mContinuousUpdateDistance = 5; // 5px need to be covered to update continuous voice
    int mDiscreteUpdateDistance = 50; // 50px need to be covered to trigger discrete voice
    int mBehaviorUpdateDistance = 25; // 50px need to be covered to trigger discrete voice
    int mPlayerUpdatePeriod = 500; // 500ms update rate, when voice is played periodically
    int waitBeforeRelease = 25; // 10ms wait before releasing voice. Prevents UDP OSC message order bug
    boolean mMeanderDisabled = false;

    // GUI options
    boolean mDrawPhiQuantRadii;
    boolean mHandModeMenuSticky;
    boolean mVoiceTypeMenuSticky;
    boolean mAlgMenuSticky;

    class TopMapSync {
        /* Object representing sync data of a single node in server. Used by GSON to parse JSON data into.
        */
        int originID;
        String linkName;
        int[] absCoords;
        int[] outLinks;
        int[] inLinks;
        int phiQuant;
    }

    class TopAlgsSync {
        /* Object representing TopAlgs sync data. A list of algorithm names Used by GSON to parse JSON data into.
        Additionally carries global data about phi-quantization
        */
        String[] genAlgs;
        String[] combAlgs;
    }

//    class TopGlobalSync { // TODO: decide if global sync is necessary
//        /* Object representing TopGlobal variable sync data. A set of options that are globally relevant to the PolyTop
//        */
//        int phiQuant; // defines the number of lineages that are accessible while playing TopMap. Divides 360 degrees equally.
//    }

    class TopAddMessage {
        /* Class that defines the structure of OSC message /topAdd. Mixed use - for both combining and generating */
        int originID; // added node originID
        int parentID; // origin node originID
        String linkName;
        double x; // absolute x
        double y; // absolute y
        ArrayList<Integer> inlinkIDs = new ArrayList<>();
        ArrayList<Integer> outlinkIDs = new ArrayList<>();

        double phi; // phi
        double l; // l
        int combActive = 0;
        String[] combTargets = {"all"};

        TopAddMessage(TopMapGuiFragment.Node child, double phi, double l, TopMapGuiFragment.Node parent) {
            /* init for generation message */
            this.originID = child.nodeID; // n.b. the ID of the origin node in this map is that of a newly created child
//            this.name = child.name; TODO remove if no bug
            this.linkName = child.linkName;
            this.x = child.x;
            this.y = child.y;
            for (TopMapGuiFragment.Node node : child.inNodes) {
                this.inlinkIDs.add(node.nodeID);
            }
            for (TopMapGuiFragment.Node node : child.outNodes) {
                this.outlinkIDs.add(node.nodeID);
            }

            this.phi = phi;
            this.l = l;
            this.parentID = parent.nodeID;

            combActive = COMB_INACTIVE; // TODO: change this in Server too
        }

        TopAddMessage(TopMapGuiFragment.Node child, double x, double y) {
            /* init for combination message */
            this.originID = child.nodeID; // n.b. the ID of the origin node in this map is that of a newly created child
//            this.name = child.name; TODO remove if no bug
            this.linkName = child.linkName;
            this.x = x;
            this.y = y;
            this.combActive = COMB_ACTIVE; // signals in Server that Add is being done on a interpolated node
        }
    }

    class TopPlayMessage {
        /* Class that defines the structure of OSC message /topPlay */
        int nodeID; // played node id

        double phi; // phi
        double l; // l
        int idx; // pointer idx (<10 in hand type, >10 in ring type)
        int type; // TYPE_DISCRETE, TYPE_CONTINUOUS, MODE_RING (TODO?)
        int pressed; // 1 at the start of DTP, 0 at release.
        int recGest; // 1 at the start of DTP, 2 at release, 0 if not recording. Active when mActivity.mGestRecActive == 1
        String genAlg;

        TopPlayMessage(TopMapGuiFragment.Node node, double phi, double l, int idx, int type, int pressed, int recGest) {
            this.nodeID = node.nodeID; // n.b. the ID of the origin node in this map is that of a newly created child

            this.phi = phi;
            this.l = l;
            this.idx = idx;
            this.type = type;
            this.pressed = pressed;
            this.recGest = recGest;
            this.genAlg = mGenAlg;
        }
    }

    class TopCombineMessage {
        /* Class that defines the structure of OSC message /topPlay */
        double x; // absolute x
        double y; // absolute y

        int idx; // pointer idx (<10 in hand type, >10 in ring type)
        int type; // TYPE_DISCRETE, TYPE_CONTINUOUS, TYPE_BEHAVIOR, MODE_RING (TODO?)
        int pressed; // 1 at the start of DTP, 0 at release.
        ArrayList<String> combTargets;
        int recGest; // 1 at the start of DTP, 2 at release, 0 if not recording. Active when mActivity.mGestRecActive == 1
        String combAlg;

        TopCombineMessage(double x, double y, int idx, int type, int pressed, ArrayList<String> combTargets, final int recGest) {

            this.x = x;
            this.y = y;
            this.idx = idx;
            this.type = type;
            this.pressed = pressed;
            this.combTargets = combTargets;
            this.recGest = recGest;
            this.combAlg = mCombAlg;
        }
    }

    /* Lifecycle methods */
    ///////////////////////

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mRootView = findViewById(R.id.touch_view);

        mAlgsMenuToggle = (FloatingActionButton) findViewById(R.id.algs_menu_toggle);
        mAlgsMenuToggle.setTitle("Algorithms");
        mAlgsMenuToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                Log.i(TAG, "AlgsMenuToggleClicked - true");
                mAlgsMenuOpen = !mAlgsMenuOpen;
                if (mAlgsMenuOpen) {
                    mAlgMenuScrollView.setVisibility(View.VISIBLE);
                    mAlgMenuScrollView.scrollTo(0, (148 + 10) * 8); // scroll to very bottom
                } else {
                    mAlgMenuScrollView.setVisibility(View.GONE);
                }
            }
        });
        mAlgMenuScrollView = (ScrollView) findViewById(R.id.algs_menu_scroll_view);

        // 0. Recover saved state
        if (savedInstanceState != null) { //on config change...
            // recreate global variables
            isSynced = savedInstanceState.getBoolean(IS_SYNCED);
            syncMessage = savedInstanceState.getString(SYNC_MESSAGE);
        }

        // 0.5 Load preferences to globals // TODO: move to seperate method once more preferences are added
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mDrawPhiQuantRadii = prefs.getBoolean("radi_visible_preference", false);
        mHandModeMenuSticky = prefs.getBoolean("hand_mode_menu_sticky", false);
        mVoiceTypeMenuSticky = prefs.getBoolean("voice_type_menu_sticky", false);
        mAlgMenuSticky = prefs.getBoolean("alg_menu_sticky", false);

        mTimedMode = prefs.getBoolean("timed_mode_on", false);
        mContinuousUpdateDistance = Integer.parseInt(prefs.getString("cont_update_dist", "5"));
        mDiscreteUpdateDistance = Integer.parseInt(prefs.getString("disc_update_distance", "50"));
        mPlayerUpdatePeriod = Integer.parseInt(prefs.getString("timed_mode_update_period", "500"));
        mMeanderDisabled = prefs.getBoolean("meander_disabled", false);


        // 1. create FAB buttons
        createFABbuttons();

        // 1.125 display pan position
        mPanPosView = (TextView) findViewById(R.id.pan_position);
        mZoomView = (TextView) findViewById(R.id.zoom);
        mPanPosView.setText("[" + -1 * mPanPos[0] + ", " + -1 * mPanPos[1] + "] "); // TODO remove code duplication
        mZoomView.setText(String.format("%.2f", mScale) + "x");

        mPanPosView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.e(TAG, "mPanPosView: setting mPanPos to origin");
                TopMapGuiFragment gui = (TopMapGuiFragment) mFragmentManager.findFragmentByTag("guiFragment");
                if (gui != null) {
                    mPanPos = new int[]{gui.width / 4, gui.height / 2};
                } else {
                    Log.i(TAG, "Pressed pan reset button before Gui fragment was initialized. Ignoring...");
                }

                mPanPosView.setText("[" + -1 * mPanPos[0] + ", " + -1 * mPanPos[1] + "] ");
            }
        });

        mZoomView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.e(TAG, "mZoomView: setting zoom to default");
                mScale = 1;
                mZoomView.setText(String.format("%.2f", mScale) + "x");
            }
        });

        // 1.25 initialize toolbar, drawer layout
        Toolbar myToolbar = (Toolbar) findViewById(R.id.my_toolbar);
        setSupportActionBar(myToolbar);

        mDrawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, mDrawer, myToolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close) {
            @Override
            public void onDrawerStateChanged(int newState) {
                /* as seen on https://stackoverflow.com/questions/23373257/how-to-detect-that-the-drawerlayout-started-opening */
                if (newState == DrawerLayout.STATE_SETTLING) {
                    if (!mDrawer.isDrawerOpen(Gravity.LEFT)) { // TODO: clarify that this is doing what it's supposed to do

                        // starts opening - cancel all sounds (since unable to track motion events)
                        TopMapGuiFragment guiFragment = ((TopMapGuiFragment) mFragmentManager.findFragmentByTag("guiFragment"));
                        if (guiFragment != null) {
                            for (int id : guiFragment.mActiveNodes.keySet()) {
                                int[] coords = guiFragment.mNodePointerCoords.get(id);
                                if (mHandMode == MODE_GEN) {
                                    guiFragment.registerPointerReleaseGen( id, coords[0], coords[1]);
                                } else if (mHandMode == MODE_COMB) {
                                    guiFragment.registerPointerReleaseComb(id, coords[0], coords[1]);
                                }
                            }
                        }
                    }
                    invalidateOptionsMenu();
                }
            }
        };
        mDrawer.setDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);
        navigationView.getMenu().getItem(1).setChecked(true); // set settings to checked

        // 1.5  initialize fragment manager for this app (NodeGraph GUI, Settings)
        mFragmentManager = getFragmentManager();

        // 1.75 display settings
        SettingsFragment newSettingsFragment = new SettingsFragment();
        mFragmentManager.beginTransaction().add(R.id.main_gui, newSettingsFragment, "settingsFragment").commit();
        hideGUIButtons();

        // 2. start listening for server sync (that will auto-init GUI)
        // TODO: this should be inside if (savedInstanceState == null). TRY
        initializeOutboundOSC();
        startSyncListening(); // n.b. GUI normally initialized on reception of sync

        // 3.
        // ... or use existing sync
        // TODO: this should be also either the case where savedInstanceState != null or == null. Simplify
        if (isSynced == true) {
            createTopMapGui(syncMessage);
        } else {
            Snackbar.make(mRootView, "Waiting for sync...", Snackbar.LENGTH_INDEFINITE).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeAllOSC();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(IS_SYNCED, isSynced);
        outState.putString(SYNC_MESSAGE, syncMessage);
    }

    /* GUI */
    /////////

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        /* Handles navigation drawer selections */
        int id = item.getItemId();
        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setCheckedItem(id);

        TopMapGuiFragment oldGui = (TopMapGuiFragment) mFragmentManager.findFragmentByTag("guiFragment");
        SettingsFragment oldSettings = (SettingsFragment) mFragmentManager.findFragmentByTag("settingsFragment");

        if (id == R.id.gui) {
            if (oldGui != null) {
                if (oldGui.isVisible()) {
                    // 1. if inside GUI fragment
                    // do nothing
                } else if (oldSettings != null) {
                    if (oldSettings.isVisible()) {
                        // 2. if inside Settings fragment
                        if (oldGui == null) {
                            // 2.1. if no GUI --> do nothing
                        } else {
                            // 2.2. if GUI exists, remove settings fragment, show GUI
                            mFragmentManager.beginTransaction().remove(oldSettings).commit();
                            mFragmentManager.beginTransaction().attach(oldGui).commit();
                            showGUIButtons();
                        }
                    }
                }
            }
        } else if (id == R.id.settings) {
            if (oldSettings != null) {
                if (oldSettings.isVisible()) {
                    // 1. if inside Settings fragment
                    // do nothing
                }
            } else if (oldGui != null) {
                if (oldGui.isVisible()) {
                    // 2. if inside GUI fragment --> hide GUI, add settings
                    mFragmentManager.beginTransaction().detach(oldGui).commit();
                    SettingsFragment settingsFragment = new SettingsFragment();
                    mFragmentManager.beginTransaction().add(R.id.main_gui, settingsFragment, "settingsFragment").commit();
                    hideGUIButtons();
                    item.setChecked(true); // set settings to checked
                }
            }
        }

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    private void createFABbuttons() {
        final FloatingActionButton cellGen = (FloatingActionButton) findViewById(R.id.cell_play);
        final FloatingActionButton cellDraw = (FloatingActionButton) findViewById(R.id.cell_draw);
        final FloatingActionButton cellDelete = (FloatingActionButton) findViewById(R.id.cell_delete);
        final FloatingActionButton cellCombine = (FloatingActionButton) findViewById(R.id.cell_combine);
        final FloatingActionButton mapPan = (FloatingActionButton) findViewById(R.id.map_pan);
//        final FloatingActionButton sendToWFS = (FloatingActionButton) findViewById(R.id.send_to_wfs);
        final FloatingActionButton recGest = (FloatingActionButton) findViewById(R.id.rec_gest);
        final FloatingActionButton undoFAB = (FloatingActionButton) findViewById(R.id.undo_fab);
        final FloatingActionButton handDiscrete = (FloatingActionButton) findViewById(R.id.hand_discrete);
        final FloatingActionButton handContinuous = (FloatingActionButton) findViewById(R.id.hand_continuous);
        final FloatingActionButton handBehavior = (FloatingActionButton) findViewById(R.id.hand_behavior);
//        final FloatingActionButton ringMode = (FloatingActionButton) findViewById(R.id.ring_mode); // disabled until next version

        cellGen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mHandMode = MODE_GEN;
                Snackbar.make(mRootView, "Generate mode selected", Snackbar.LENGTH_SHORT).show();
                if (!mHandModeMenuSticky) {
                    ((FloatingActionsMenu) v.getParent()).collapse();
                }
            }
        });
        cellDraw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDrawActive = !mDrawActive;
                if (mDrawActive) {
                    cellDraw.setIcon(R.drawable.cell_draw_on_2);
                    Snackbar.make(mRootView, "Drawing active", Snackbar.LENGTH_SHORT).show();
                } else {
                    cellDraw.setIcon(R.drawable.cell_draw_off_2);
                    Snackbar.make(mRootView, "Drawing inactive", Snackbar.LENGTH_SHORT).show();
                }
                if (!mHandModeMenuSticky) {
                    ((FloatingActionsMenu) v.getParent()).collapse();
                }
            }
        });
        cellDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mHandMode = MODE_DEL;
                Snackbar.make(mRootView, "Delete mode selected", Snackbar.LENGTH_SHORT).show();
                if (!mHandModeMenuSticky) {
                    ((FloatingActionsMenu) v.getParent()).collapse();
                }
            }
        });
        cellCombine.setOnClickListener(new View.OnClickListener() { // Disabled until next version
            @Override
            public void onClick(View v) {
                mHandMode = MODE_COMB;
                Snackbar.make(mRootView, "Combine mode selected", Snackbar.LENGTH_SHORT).show();
                if (!mHandModeMenuSticky) {
                    ((FloatingActionsMenu) v.getParent()).collapse();
                }
            }
        });
        mapPan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mHandMode = MODE_PAN;
                Snackbar.make(mRootView, "Pan mode selected", Snackbar.LENGTH_SHORT).show();
                if (!mHandModeMenuSticky) {
                    ((FloatingActionsMenu) v.getParent()).collapse();
                }
            }
        });

//        sendToWFS.setOnClickListener(new View.OnClickListener() { // Disabled until next version
//            @Override
//            public void onClick(View v) {
//                mHandMode = MODE_WFS;
//                if (!mHandModeMenuSticky) {
//                    ((FloatingActionsMenu) v.getParent()).collapse();
//                }
//            }
//        });

        recGest.setOnClickListener(new View.OnClickListener() { // Disabled until next version
            @Override
            public void onClick(View v) {
                mGestRecActive = !mGestRecActive;
                if (mGestRecActive) {
                    recGest.setIcon(R.drawable.record_gesture_on);
                    Snackbar.make(mRootView, "Gesture recording on", Snackbar.LENGTH_SHORT).show();
                } else {
                    recGest.setIcon(R.drawable.record_gesture_off);
                    Snackbar.make(mRootView, "Gesture recording off", Snackbar.LENGTH_SHORT).show();
                }
                if (!mHandModeMenuSticky) {
                    ((FloatingActionsMenu) v.getParent()).collapse();
                }
            }
        });

        undoFAB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mFragmentManager != null) {
                    TopMapGuiFragment guiFragment = (TopMapGuiFragment) mFragmentManager.findFragmentByTag("guiFragment");
                    if (guiFragment != null) {
                        if (guiFragment.mAddedNodeHistory.size() != 0) {
                            TopMapGuiFragment.Node undoneNode = guiFragment.mAddedNodeHistory.remove(guiFragment.mAddedNodeHistory.size() - 1);
                            if (undoneNode != null) {
                                guiFragment.deleteNodeCompletely(undoneNode); // delete added node
                            }
                        }
                    }
                }
            }
        });
        handDiscrete.setOnClickListener(new View.OnClickListener() { // Disabled until next version
            @Override
            public void onClick(View v) {
                mVoiceType = TYPE_DISCRETE;
                Snackbar.make(mRootView, "Discrete voice selected", Snackbar.LENGTH_SHORT).show();

                if (!mVoiceTypeMenuSticky) {
                    ((FloatingActionsMenu) v.getParent()).collapse();
                }
            }
        });
        handContinuous.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mVoiceType = TYPE_CONTINUOUS;
                Snackbar.make(mRootView, "Continuous voice selected", Snackbar.LENGTH_SHORT).show();

                if (!mVoiceTypeMenuSticky) {
                    ((FloatingActionsMenu) v.getParent()).collapse();
                }
            }
        });
        handBehavior.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mVoiceType = TYPE_BEHAVIOR;
                Snackbar.make(mRootView, "Behavior voice selected", Snackbar.LENGTH_SHORT).show();

                if (!mVoiceTypeMenuSticky) {
                    ((FloatingActionsMenu) v.getParent()).collapse();
                }
            }
        });
//        ringMode.setOnClickListener(new View.OnClickListener() { // Disabled until next version
//            @Override
//            public void onClick(View v) {
////                mVoiceType = MODE_RING;
//                // TODO: implement ring type
//        if (mHandModeMenuSticky) {
//                ((FloatingActionsMenu) v.getParent()).collapse();
//    }
//            }
//        });

    }

    private void hideGUIButtons() {
        /* called when moving to a settings fragment */
        final FloatingActionButton menuAlgsToggle = (FloatingActionButton) findViewById(R.id.algs_menu_toggle);
        final ScrollView menuAlgsScrollView = (ScrollView) findViewById(R.id.algs_menu_scroll_view);

        final FloatingActionsMenu menuActions = (FloatingActionsMenu) findViewById(R.id.voice_mode);
        final FloatingActionsMenu menuModes = (FloatingActionsMenu) findViewById(R.id.multiple_actions);
        FloatingActionButton undoFAB = (FloatingActionButton) findViewById(R.id.undo_fab);

        menuAlgsToggle.setVisibility(View.INVISIBLE);
        menuAlgsScrollView.setVisibility(View.INVISIBLE);
        menuActions.setVisibility(View.INVISIBLE);
        menuModes.setVisibility(View.INVISIBLE);
        undoFAB.setVisibility(View.INVISIBLE);

        mPanPosView.setVisibility(View.INVISIBLE);
        mZoomView.setVisibility(View.INVISIBLE);
    }

    private void showGUIButtons() {
        /* called when moving to a GUI fragment */

        final FloatingActionButton menuAlgsToggle = (FloatingActionButton) findViewById(R.id.algs_menu_toggle);
//        final ScrollView menuAlgsScrollView = (ScrollView) findViewById(R.id.algs_menu_scroll_view);

        final FloatingActionsMenu menuActions = (FloatingActionsMenu) findViewById(R.id.voice_mode);
        final FloatingActionsMenu menuModes = (FloatingActionsMenu) findViewById(R.id.multiple_actions);
        FloatingActionButton undoFAB = (FloatingActionButton) findViewById(R.id.undo_fab);

        menuAlgsToggle.setVisibility(View.VISIBLE);
        menuActions.setVisibility(View.VISIBLE);
        menuModes.setVisibility(View.VISIBLE);
        undoFAB.setVisibility(View.VISIBLE);

        mPanPosView.setVisibility(View.VISIBLE);
        mZoomView.setVisibility(View.VISIBLE);
    }

    private void createAlgFABbuttons(String syncTopAlgsMessage) {
        TopAlgsSync topAlgsSync = mGson.fromJson(syncTopAlgsMessage, TopAlgsSync.class);
        int maxChildWidth = 0;

        final LinearLayout algsLayout = (LinearLayout) findViewById(R.id.algs_menu);

        // cleanly remove all buttons at each sync
        if (mFabAlgButtons != null) {
            for (LinearLayout buttonWithLabel : mFabAlgButtons) {
                Log.i(TAG, "creteAlgFABButtons: FAB menu exists - remove each button from FAB menu, before re-adding them");
                algsLayout.removeView(buttonWithLabel);
            }
            mFabAlgButtons.clear();
        } else {
            Log.i(TAG, "creteAlgFABButtons: FAB menu does not exist - create new empty list for buttons");
            mFabAlgButtons = new ArrayList<>();
        }

        // add gen algs
        for (final String algName : topAlgsSync.genAlgs) { // for every synced algorithm, create a FAB button
            Log.i(TAG, "creteAlgFABButtons: creating buttons for gen algs" + topAlgsSync.genAlgs);
            FloatingActionButton algButton = new FloatingActionButton(getBaseContext());
            algButton.setColorNormal(getColor(R.color.half_black));
            algButton.setColorPressed(getColor(R.color.electric_pressed));
            algButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mGenAlg = algName;
//                    sendTopSetGenAlg();
                    if (!mAlgMenuSticky) {
                        mAlgMenuScrollView.setVisibility(View.GONE);
                        mAlgsMenuOpen = false;
                    }
                }
            });
            int childWidth = algButton.getWidth();
            maxChildWidth = childWidth > maxChildWidth ? childWidth : maxChildWidth; // update max width

            TextView label = new TextView(this);
            label.setText(algName);
            label.setTextAppearance(R.style.menu_labels_style);

            LinearLayout buttonWithLabel = new LinearLayout(this);
            buttonWithLabel.setLayoutParams(new DrawerLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            buttonWithLabel.setGravity(Gravity.CENTER);


            buttonWithLabel.addView(algButton);
            buttonWithLabel.addView(label);

            algsLayout.addView(buttonWithLabel);
            mFabAlgButtons.add(buttonWithLabel);
        }

        // add comb algs
        for (final String algName : topAlgsSync.combAlgs) { // for every synced algorithm, create a FAB button
            Log.i(TAG, "creteAlgFABButtons: creating buttons for comb algs" + topAlgsSync.combAlgs);
            FloatingActionButton algButton = new FloatingActionButton(getBaseContext());
            algButton.setTitle(algName);
            algButton.setColorNormal(getColor(R.color.dark_gray));
            algButton.setColorPressed(getColor(R.color.electric_pressed));
            algButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mCombAlg = algName;
//                    sendTopSetCombAlg();
                    if (!mAlgMenuSticky) {
                        mAlgMenuScrollView.setVisibility(View.GONE);
                        mAlgsMenuOpen = false;
                    }
                }
            });

            TextView label = new TextView(this);
            label.setText(algName);
            label.setTextAppearance(R.style.menu_labels_style);

            LinearLayout buttonWithLabel = new LinearLayout(this);
            buttonWithLabel.setLayoutParams(new DrawerLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            buttonWithLabel.setGravity(Gravity.CENTER);

            buttonWithLabel.addView(algButton);
            buttonWithLabel.addView(label);

            algsLayout.addView(buttonWithLabel);
            mFabAlgButtons.add(buttonWithLabel);
        }

        if (topAlgsSync.combAlgs != null) {
            mCombAlg = topAlgsSync.combAlgs[0];
        }
    }

//    public void sendTopSetGenAlg() {
//    /* deprecated since algs are sent with /topPlay and /topComb messages */
//
//        Thread sendTopSetGenAlgOSC = new Thread(new Runnable() {
//            @Override
//            public void run() {
//                if (mGenAlg == "nil") {
//                    Log.i(TAG, "sendTopSetGenAlg(): mGenAlg is nil, returning without sending");
//                    return;
//                }
//
//                if (mOscPortOut != null) {
//                    Object[] thingsToSend = new Object[1];
//                    // Strange mistake - OSC message doesn't arrive dependent on the content
////                    thingsToSend[0] = Integer.toString(x) + ", " + Integer.toString(y);                 // Won't be received by SC?
//
//                    thingsToSend[0] = "PolyTop, " + mGenAlg;   // Received fine!
//
//                    OSCMessage message = new OSCMessage(OSC_SET_GENALG, Arrays.asList(thingsToSend));
//                    try {
//                        mOscPortOut.send(message);
//                        Snackbar.make(mRootView, "Selecting gen algorithm - name: " + mGenAlg, Snackbar.LENGTH_LONG).show(); //DEBUG
////                        Log.i(TAG, "OSC port state: " + mOscPortOut); // DEBUG
////                        Log.i(TAG, "Trying to send to IP, port: " + mServerIP + ", " + SERVER_PORT); // DEBUG
//                    } catch (Exception e) {
//                        Log.e(TAG, "Failed to send OSC message. Check IP or port number");
//                    }
//                } else {
//                    Log.e(TAG, "Outbound OSC port null!");
//                }
//            }
//        });
//        sendTopSetGenAlgOSC.start();
//    }

//    public void sendTopSetCombAlg() {
//    /* deprecated since algs are sent with /topPlay and /topComb messages */
//        Thread sendTopSetCombAlgOSC = new Thread(new Runnable() {
//            @Override
//            public void run() {
//                if (mCombAlg == "nil") {
//                    Log.i(TAG, "sendTopSetGenAlg(): mCombAlg is nil, returning without sending");
//                    return;
//                }
//
//                if (mOscPortOut != null) {
//                    Object[] thingsToSend = new Object[1];
//                    // Strange mistake - OSC message doesn't arrive dependent on the content
////                    thingsToSend[0] = Integer.toString(x) + ", " + Integer.toString(y);                 // Won't be received by SC?
//
//                    thingsToSend[0] = "PolyTop, " + mCombAlg;   // Received fine!
//
//                    OSCMessage message = new OSCMessage(OSC_SET_COMBALG, Arrays.asList(thingsToSend));
//                    try {
//                        mOscPortOut.send(message);
//                        Snackbar.make(mRootView, "Selecting comb algorithm - name: " + mCombAlg, Snackbar.LENGTH_LONG).show(); //DEBUG
////                        Log.i(TAG, "OSC port state: " + mOscPortOut); // DEBUG
////                        Log.i(TAG, "Trying to send to IP, port: " + mServerIP + ", " + SERVER_PORT); // DEBUG
//                    } catch (Exception e) {
//                        Log.e(TAG, "Failed to send OSC message. Check IP or port number");
//                    }
//                } else {
//                    Log.e(TAG, "Outbound OSC port null!");
//                }
//            }
//        });
//        sendTopSetCombAlgOSC.start();
//    }


    private void createTopMapGui(String rawSyncData) {

        // TODO JSON error check

        // 1. Load TopMapGuiFragment
        final TopMapGuiFragment topMapGui = new TopMapGuiFragment();

        // 2. pass sync message as ARGS to fragment.
        Bundle guiArgs = new Bundle();
        guiArgs.putString("rawSyncData", rawSyncData);
        topMapGui.setArguments(guiArgs);

        TopMapGuiFragment oldGui = (TopMapGuiFragment) mFragmentManager.findFragmentByTag("guiFragment");
        SettingsFragment settingsFragment = (SettingsFragment) mFragmentManager.findFragmentByTag("settingsFragment");

        //init a new node map GUI from given sync
        if (oldGui != null) {
            if (oldGui.isVisible()) {
                // 1. if inside GUI
                mFragmentManager.beginTransaction().replace(R.id.main_gui, topMapGui, "guiFragment").commit();
            }
        } else if (settingsFragment != null) {
            if (settingsFragment.isVisible()) {
                if (oldGui == null) {
                    mFragmentManager.beginTransaction().replace(R.id.main_gui, topMapGui, "guiFragment").commit();
                } else {
                    mFragmentManager.beginTransaction().remove(oldGui).commit();
                    mFragmentManager.beginTransaction().replace(R.id.main_gui, topMapGui, "guiFragment").commit();
                }
            }
        }
    }

    // Outgoing updates: Client --> Server //
    /////////////////////////////////////////

    void initializeOutboundOSC() {
        try {
            // Connect to some IP address and port
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
            mServerIP = sharedPref.getString("server_ip_preference", SERVER_IP);
            mOscPortOut = new OSCPortOut(InetAddress.getByName(mServerIP), SERVER_PORT);
        } catch (UnknownHostException e) {
            Log.e(TAG, "Couldn't find IP of Server!");
            return;
        } catch (Exception e) {
            Log.e(TAG, "Unknown issue when opening OSC port!");
            return;
        }
    }

    public void sendTopPlayOSC(final TopMapGuiFragment.Node node, final int x, final int y, final int pointerIdx, final int voiceMode, final int pressed, final int recGest) {
        // cartesian (absolute) to polar (relative to node)
        int xD = node.x - x;
        int yD = node.y - y;
        Log.i(TAG, "debug1: [xD, yD] - [" + xD + ", " + yD + "]");

        final double l = Math.sqrt(xD * xD + yD * yD) + 1; // TODO: find solution to 0 int problem. Currently just indexed from 1+.
        final double phi = Math.acos(xD / l) * Integer.signum(yD);

        Log.i(TAG, "sendTopPlayOSC(): nodeID " + node.nodeID + ", pointerIdx " + pointerIdx + ", phi " + Double.toString(phi) + ", l " + Double.toString(l));

        Thread sendTopPlayOSC = new Thread(new Runnable() {
            @Override
            public void run() {
                if (mOscPortOut != null) {
                    Object[] thingsToSend = new Object[1];

                    TopPlayMessage topPlayMessage = new TopPlayMessage(node, phi, l, pointerIdx, voiceMode, pressed, recGest);
                    thingsToSend[0] = mGson.toJson(topPlayMessage, TopPlayMessage.class);
                    // Strange mistake - OSC message doesn't arrive dependent on the content
//                    thingsToSend[0] = Integer.toString(x) + ", " + Integer.toString(y);                 // Won't be received by SC?

                    OSCMessage message = new OSCMessage(OSC_PLAY, Arrays.asList(thingsToSend));
                    try {
                        mOscPortOut.send(message);
//                        Snackbar.make(mRootView, "Playing Node - originID: " + Integer.toString(node.nodeID) + "; Phi,l: " + Double.toString(Math.round(phi)) + ", " + Double.toString(Math.round(l)) + ".", Snackbar.LENGTH_LONG).show(); //DEBUG
//                        Log.i(TAG, "OSC port state: " + mOscPortOut); // DEBUG
//                        Log.i(TAG, "Trying to send to IP, port: " + mServerIP + ", " + SERVER_PORT); // DEBUG
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send OSC message. Check IP or port number");
                    }
                } else {
                    Log.e(TAG, "Outbound OSC port null!");
                }
            }
        });
        sendTopPlayOSC.start();
    }

    public void sendTopCombineOSC(final int x, final int y, final int pointerIdx, final int voiceMode, final int pressed, final int recGest) {
        Log.i(TAG, "sendTopCombineOSC(): abs coords - x " + Double.toString(x) + ", y " + Double.toString(y) + ", pointerId " + pointerIdx + ", voiceMode " + voiceMode + ", pressed " + pressed + ", recGest " + recGest);

        Thread sendTopCombineOSC = new Thread(new Runnable() {
            @Override
            public void run() {
                if (mOscPortOut != null) {
                    Object[] thingsToSend = new Object[1];

                    ArrayList<String> combTargets = null;
                    if (mFragmentManager != null) {
                        TopMapGuiFragment guiFragment = (TopMapGuiFragment) mFragmentManager.findFragmentByTag("guiFragment");
                        if (guiFragment != null) {
                            combTargets = new ArrayList(guiFragment.mCombTargets.keySet());
                        }
                    }
                    if (combTargets.size() == 0) {
                        combTargets = new ArrayList<>(Arrays.asList(new String[]{"all"}));
                        Log.i(TAG, "sentTopCombineOSC(): no comb targets, will not send combine message");
                    }
                    TopCombineMessage topCombineMessage = new TopCombineMessage(x, y, pointerIdx, voiceMode, pressed, combTargets, recGest);
                    thingsToSend[0] = mGson.toJson(topCombineMessage, TopCombineMessage.class);
                    // Strange mistake - OSC message doesn't arrive dependent on the content
//                    thingsToSend[0] = Integer.toString(x) + ", " + Integer.toString(y);                 // Won't be received by SC?

                    OSCMessage message = new OSCMessage(OSC_COMB, Arrays.asList(thingsToSend));
                    try {
                        mOscPortOut.send(message);
//                        Log.i(TAG, "OSC port state: " + mOscPortOut); // DEBUG
//                        Log.i(TAG, "Trying to send to IP, port: " + mServerIP + ", " + SERVER_PORT); // DEBUG
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send OSC message. Check IP or port number");
                    }
                } else {
                    Log.e(TAG, "Outbound OSC port null!");
                }
            }
        });
        sendTopCombineOSC.start();
    }

    public void sendTopWFSOSC(final TopMapGuiFragment.Node node) {

        Log.i(TAG, "sendTopPlayOSC(): nodeID " + node.nodeID);

        Thread sendTopWFSOSC = new Thread(new Runnable() {
            @Override
            public void run() {
                if (mOscPortOut != null) {
                    Object[] thingsToSend = new Object[1];
                    // Strange mistake - OSC message doesn't arrive dependent on the content
//                    thingsToSend[0] = Integer.toString(x) + ", " + Integer.toString(y);                 // Won't be received by SC?
                    thingsToSend[0] = "PolyTop, " + Integer.toString(node.nodeID);   // Received fine!

                    OSCMessage message = new OSCMessage(OSC_WFS, Arrays.asList(thingsToSend));
                    try {
                        mOscPortOut.send(message);
                        Snackbar.make(mRootView, "Sending node to WFS_COLLIDER - originID: " + Integer.toString(node.nodeID), Snackbar.LENGTH_LONG).show(); //DEBUG
//                        Log.i(TAG, "OSC port state: " + mOscPortOut); // DEBUG
//                        Log.i(TAG, "Trying to send to IP, port: " + mServerIP + ", " + SERVER_PORT); // DEBUG
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send OSC message. Check IP or port number");
                    }
                } else {
                    Log.e(TAG, "Outbound OSC port null!");
                }
            }
        });
        sendTopWFSOSC.start();
    }

    public void sendTopAddOSC(final TopMapGuiFragment.Node origin, final TopMapGuiFragment.Node child, final int x, final int y) {
        // create send message
        final TopAddMessage topAddMessage;
        if (mHandMode == MODE_GEN) {
            // cartesian (absolute) to polar (relative to node)
            int xD = origin.x - x;
            int yD = origin.y - y;
            final double l = Math.sqrt(xD * xD + yD * yD) + 1; // TODO: find solution to 0 int problem. Currently just indexed from 1+.
            final double phi = Math.acos(xD / l) * Integer.signum(yD);

            Log.i(TAG, "Adding Node - originID: " + Integer.toString(origin.nodeID) + "; Phi,l: " + Double.toString(Math.round(phi)) + ", " + Double.toString(Math.round(l)) + "; Abs coords: " + Integer.toString(x) + ", " + Integer.toString(y) + "."); //DEBUG
            topAddMessage = new TopAddMessage(child, phi, l, origin);
        } else if (mHandMode == MODE_COMB) {
            topAddMessage = new TopAddMessage(child, x, y);
        } else {
            topAddMessage = null;
            Log.e(TAG, "sendTopAddOSC: topAddMessage == null, because of type error");
        }


        Thread sendTopAddOSC = new Thread(new Runnable() {
            @Override
            public void run() {
                if (mOscPortOut != null) {
                    Object[] thingsToSend = new Object[1];

                    thingsToSend[0] = mGson.toJson(topAddMessage, TopAddMessage.class);
                    OSCMessage message = new OSCMessage(OSC_ADD, Arrays.asList(thingsToSend));
                    try {
                        mOscPortOut.send(message);
//                        Log.i(TAG, "OSC port state: " + mOscPortOut); // DEBUG
//                        Log.i(TAG, "Trying to send to IP, port: " + mServerIP + ", " + SERVER_PORT); // DEBUG
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send OSC message. Check IP or port number");
                    }
                } else {
                    Log.e(TAG, "Outbound OSC port null!");
                }
            }
        });
        sendTopAddOSC.start();
    }

    public void sendTopDeleteOSC(final int originID) {
        Thread sendTopDeleteOSC = new Thread(new Runnable() {
            @Override
            public void run() {
                if (mOscPortOut != null) {
                    Object[] thingsToSend = new Object[1];
                    thingsToSend[0] = "PolyTop, " + Integer.toString(originID);   // Received fine!

                    OSCMessage message = new OSCMessage(OSC_DELETE, Arrays.asList(thingsToSend));
                    try {
                        mOscPortOut.send(message);
//                        Snackbar.make(mRootView, "OriginID: " + Integer.toString(originID) + " deleted", Snackbar.LENGTH_SHORT).show();
//                        Log.i(TAG, "OSC port state: " + mOscPortOut); // DEBUG
//                        Log.i(TAG, "Trying to send to IP, port: " + mServerIP + ", " + SERVER_PORT); // DEBUG
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send OSC message. Check IP or port number");
                    }
                } else {
                    Log.e(TAG, "Outbound OSC port null!");
                }
            }
        });
        sendTopDeleteOSC.start();
    }

    public void sendTopSyncReply() {
        Thread sendTopSyncReply = new Thread(new Runnable() {
            @Override
            public void run() {
                if (mOscPortOut != null) {
                    Object[] thingsToSend = new Object[1];
                    thingsToSend[0] = "PolyTop, ";   // Received fine!

                    OSCMessage message = new OSCMessage(OSC_SYNC_REPLY, Arrays.asList(thingsToSend));
                    try {
                        mOscPortOut.send(message);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send OSC message. Check IP or port number");
                    }
                } else {
                    Log.e(TAG, "Outbound OSC port null!");
                }
            }
        });
        sendTopSyncReply.start();
    }

    public void closeAllOSC() {
        if (mOscPortIn != null) {
            // JavaOSC cleanup
            mOscPortIn.stopListening();
            mOscPortIn.close();
        }
        if (mOscPortOut != null) {
            mOscPortOut.close();
        }
    }

    // Incoming updates: Server --> Client //
    /////////////////////////////////////////

    void startSyncListening() {
        // Starts listening to syncs from Server

        // TopSync
        OSCListener syncListener = new OSCListener() {
            // Listen for a short sync message
            public void acceptMessage(java.util.Date time, OSCMessage message) {
                Log.i(TAG, "Sync received!");
                try {
                    syncMessage = (String) message.getArguments().get(0);
                } catch (Exception e) {
                    Log.e(TAG, "Sync format invalid! Perhaps no data was set on OSC message?...");
                    return;
                }

                isSynced = true;
                sendTopSyncReply();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // n.b. changes here must be mirrored in syncPartEndListener! TODO: rewrite without duplicate code
                        createTopMapGui(syncMessage);
                        showGUIButtons();
                    }
                });
                Snackbar.make(mRootView, "Synced!", Snackbar.LENGTH_LONG).show();
            }
        };
        OSCListener syncPartListener = new OSCListener() {
            // Listen for a fragmented sync message (due to OSC size restrictions)
            public void acceptMessage(java.util.Date time, OSCMessage message) {
                Log.i(TAG, "Sync part received!");
                try {
                    syncPartMessage = syncPartMessage + ((String) message.getArguments().get(0));
                } catch (Exception e) {
                    Log.e(TAG, "Sync format invalid! Perhaps no data was set in OSC message?...");
                    return;
                }
            }
        };


        OSCListener syncPartEndListener = new OSCListener() {
            // Listen for a fragmented sync message end
            public void acceptMessage(java.util.Date time, OSCMessage message) {
                Log.i(TAG, "Sync end received!");

                syncMessage = syncPartMessage;
                syncPartMessage = "";
                isSynced = true;
                sendTopSyncReply();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        createTopMapGui(syncMessage);
                        showGUIButtons();
                    }
                });
                Snackbar.make(mRootView, "Synced!", Snackbar.LENGTH_LONG).show();
            }
        };

        // TopAlgsSync
        OSCListener syncTopAlgsListener = new OSCListener() {
            // Listen for a short sync message
            public void acceptMessage(java.util.Date time, OSCMessage message) {
                Log.i(TAG, "TopAlg sync received!");
                try {
                    final String syncTopAlgsMessage = (String) message.getArguments().get(0);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            createAlgFABbuttons(syncTopAlgsMessage);
                        }
                    });
                    Snackbar.make(mRootView, "TopAlgs synced!", Snackbar.LENGTH_LONG).show();
                } catch (Exception e) {
                    Log.e(TAG, "Sync format invalid! Perhaps no data was set on OSC message?...");
                    return;
                }
            }
        };

        // TopGlobalSync // TODO: decide if global sync is necessary
        /* OSCListener syncGlobalListener = new OSCListener() {
            // Listen for a short sync message
            public void acceptMessage(java.util.Date time, OSCMessage message) {
                Log.i(TAG, "TopGlobal sync received!");
                try {
                    String syncTopGlobalMessage = (String) message.getArguments().get(0);
                    mTopGlobalSync = mGson.fromJson(syncTopGlobalMessage, TopGlobalSync.class);
                } catch (Exception e) {
                    Log.e(TAG, "Sync format invalid! Perhaps no data was set on OSC message?...");
                    return;
                }


                Snackbar.make(mRootView, "TopAlgs synced!", Snackbar.LENGTH_LONG).show();
            }
        };
        */

        try {
            mOscPortIn = new OSCPortIn(CLIENT_PORT);
            mOscPortIn.addListener(OSC_SYNC, syncListener); // Listen to /syncTop messages
            mOscPortIn.addListener(OSC_SYNC_PART, syncPartListener); // Listen to /topSyncPart messages
            mOscPortIn.addListener(OSC_SYNC_PART_END, syncPartEndListener); // Listen to /topSyncPartEnd messages
            mOscPortIn.addListener(OSC_TOP_ALGS_SYNC, syncTopAlgsListener); // Listen to /topAlgsSync messages
//            mOscPortIn.addListener(OSC_TOP_GLOBAL_SYNC, syncGlobalListener); // Listen to /syncPolyTop messages //TODO: decide if global sync is necessary

            mOscPortIn.startListening();
        } catch (SocketException e) {
            Log.e(TAG, "Couldn't open port for sync listening!");
        }
    }
}


