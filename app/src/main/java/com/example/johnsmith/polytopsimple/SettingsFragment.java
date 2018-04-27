package com.example.johnsmith.polytopsimple;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.View;

import com.google.common.net.InetAddresses;

public class SettingsFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    // Misc constants
    private static final String TAG_SETTINGS = "settings_fragment";

    // Globals
    MainActivity mActivity;

    ///////////////////////
    /* Lifecycle methods */

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mActivity = (MainActivity) getActivity();

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.settings);
        // Set correct PolyTop client IP
        Preference clientIPPreference = findPreference("client_ip_preference");
        clientIPPreference.setSummary(getPolyTopIP());
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        updatePlaybackSettingGUI();
    }

    /* Lifecycle methods */
    ///////////////////////

    ///////////////
    /* Listeners */

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Log.i(TAG_SETTINGS, "Settings changed!");
        if (key.equals("server_ip_preference")) { // TODO: change to XML-defined relative key name
            String newIpAddress = sharedPreferences.getString(key, "ERROR, null value should not be retrieved");
            Log.i(TAG_SETTINGS, "Settings changed: Server IP address - " + newIpAddress);
            mActivity.mServerIP = newIpAddress;

            Thread releaseWaitRestartOSC = new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        mActivity.closeAllOSC();
                        Thread.sleep(250); // sleep 0.25s before opening another connection
                        mActivity.initializeOutboundOSC();
                        mActivity.startSyncListening();
                    } catch (InterruptedException e) {
                        // do nothing
                    }
                }
            });
            releaseWaitRestartOSC.run();
        }

        if (key.equals("radi_visible_preference")) {
            Boolean radiiVisible = sharedPreferences.getBoolean(key, false);
            mActivity.mDrawPhiQuantRadii = radiiVisible;
        }

        if (key.equals("meander_disabled")) {
            Boolean meanderDisabled = sharedPreferences.getBoolean(key, false);
            mActivity.mMeanderDisabled = meanderDisabled;
        }

        if (key.equals("hand_mode_menu_sticky")) {
            Boolean handModeMenuSticky = sharedPreferences.getBoolean(key, false);
            mActivity.mHandModeMenuSticky = handModeMenuSticky;
        }

        if (key.equals("voice_type_menu_sticky")) {
            Boolean voiceTypeMenuSticky = sharedPreferences.getBoolean(key, false);
            mActivity.mVoiceTypeMenuSticky = voiceTypeMenuSticky;
        }

        if (key.equals("alg_menu_sticky")) {
            Boolean algMenuSticky = sharedPreferences.getBoolean(key, false);
            mActivity.mAlgMenuSticky = algMenuSticky;
        }

        if (key.equals("movement_mode_on")) {
            mActivity.mTimedMode = !(sharedPreferences.getBoolean(key, false));
            Log.i(TAG_SETTINGS, "Settings changed: movement type is " + !mActivity.mTimedMode);
            CheckBoxPreference timedModePreference = (CheckBoxPreference) findPreference("timed_mode_on");
            EditTextPreference timedModePeriodPreference = (EditTextPreference) findPreference("timed_mode_update_period");
            if (mActivity.mTimedMode == true) {
                timedModePreference.setChecked(true);
                timedModePeriodPreference.setEnabled(true);
            } else {
                timedModePreference.setChecked(false);
                timedModePeriodPreference.setEnabled(false);

            }
        }

        if (key.equals("dtp_update_distance")) {
            Integer discPx = Integer.parseInt(sharedPreferences.getString(key, "50")); // TODO: change default to static in all places
            mActivity.mDiscreteUpdateDistance = discPx;
            Log.i(TAG_SETTINGS, "Settings changed: mDiscreteUpdateDistance " + mActivity.mDiscreteUpdateDistance);
        }

        if (key.equals("cont_update_distance")) {
            Integer contPx = Integer.parseInt(sharedPreferences.getString(key, "5")); // TODO: change default to static in all places
            mActivity.mDiscreteUpdateDistance = contPx;
            Log.i(TAG_SETTINGS, "Settings changed: mDiscreteUpdateDistance " + mActivity.mDiscreteUpdateDistance);

        }

        if (key.equals("timed_mode_on")) {
            mActivity.mTimedMode = sharedPreferences.getBoolean(key, false);
            Log.i(TAG_SETTINGS, "Settings changed: tempDebugSequencedMode is " + mActivity.mTimedMode);
            CheckBoxPreference movementModePreference = (CheckBoxPreference) findPreference("movement_mode_on");
            EditTextPreference dtpUpdateDistPreference = (EditTextPreference) findPreference("disc_update_distance");
            EditTextPreference contUpdateDistPreference = (EditTextPreference) findPreference("cont_update_distance");

            if (mActivity.mTimedMode == true) {
                movementModePreference.setChecked(false);
                dtpUpdateDistPreference.setEnabled(false);
                contUpdateDistPreference.setEnabled(false);


            } else {
                movementModePreference.setChecked(true);
                dtpUpdateDistPreference.setEnabled(true);
                contUpdateDistPreference.setEnabled(true);
            }
        }

        if (key.equals("timed_mode_update_period")) {
            Integer ms = Integer.parseInt(sharedPreferences.getString(key, "500")); // TODO: change default to static in all places
            mActivity.mPlayerUpdatePeriod = ms;
            Log.i(TAG_SETTINGS, "Settings changed: mPlayerUpdatePeriod " + mActivity.mPlayerUpdatePeriod);
        }


    }

    ////////////////////
    // Helper methods //


    private void updatePlaybackSettingGUI() {

        CheckBoxPreference timedModePreference = (CheckBoxPreference) findPreference("timed_mode_on");
        EditTextPreference timedModePeriodPreference = (EditTextPreference) findPreference("timed_mode_update_period");
        CheckBoxPreference movementModePreference = (CheckBoxPreference) findPreference("movement_mode_on");
        EditTextPreference dtpUpdateDistPreference = (EditTextPreference) findPreference("disc_update_distance");
        EditTextPreference contUpdateDistPreference = (EditTextPreference) findPreference("cont_update_distance");
        if (PreferenceManager.getDefaultSharedPreferences(getContext()).getBoolean("timed_mode_on", false) == true)
        {
            timedModePreference.setChecked(true);
            timedModePeriodPreference.setEnabled(true);
            movementModePreference.setChecked(false);
            dtpUpdateDistPreference.setEnabled(false);
            contUpdateDistPreference.setEnabled(false);
        } else {
            timedModePreference.setChecked(false);
            timedModePeriodPreference.setEnabled(false);
            movementModePreference.setChecked(true);
            dtpUpdateDistPreference.setEnabled(true);
            contUpdateDistPreference.setEnabled(true);
        }
    }

    public String getPolyTopIP() {
        WifiManager wifii = (WifiManager) mActivity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        String s_ipAddress;

        DhcpInfo d = wifii.getDhcpInfo();
        // get and reverse IP address
        String reversed = InetAddresses.fromInteger(d.ipAddress).getHostAddress();
        String[] address = reversed.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = address.length - 1; i >= 0; i--) {
            sb.append(address[i]);
            if (i != 0) {
                sb.append(".");
            }
        }
        Log.e(TAG_SETTINGS, "getPolyTopIP: sb -  " + sb.toString());


        if (sb.toString().equals("0.0.0.0")) {
            s_ipAddress = "192.168.43.1 (hotspot, this device)";
        } else {
            s_ipAddress = "" + sb.toString() + " (wifi, this device)";
        }
        Log.e(TAG_SETTINGS, "getPolyTopIP: IP Address: " + s_ipAddress);
        return s_ipAddress;
    }
}

