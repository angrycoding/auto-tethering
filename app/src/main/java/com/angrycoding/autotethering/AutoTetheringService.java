package com.angrycoding.autotethering;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.lang.reflect.Method;
import java.util.List;

public class AutoTetheringService  extends AccessibilityService {

    private int WIFI_AP_STATE_DISABLED = 11;
    private int WIFI_AP_STATE_ENABLED = 13;
    private int WIFI_AP_STATE_FAILED = 14;

    private String WIFI_AP_STATE_CHANGED_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private String WIFI_HOTSPOT_CLIENTS_CHANGED_ACTION = "android.net.wifi.WIFI_HOTSPOT_CLIENTS_CHANGED";

    private long UPDATE_STATE_INTERVAL_MS = 10000;
    private long WAIT_FOR_CONNECTION_MS = 60000;
    private String CAR_WIFI_BEACON_NAME = "mazda6-wifi-beacon";

    private Context context;
    private WifiManager wifiManager;
    private Class wifiManagerClass = WifiManager.class;
    private boolean isServiceRunning = false;

    private void playSound(int sound) {
        AssetFileDescriptor afd = context.getResources().openRawResourceFd(sound);
        MediaPlayer player = new MediaPlayer();
        player.setAudioStreamType(AudioManager.STREAM_RING);
        try {
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            player.prepare();
        }
        catch (Exception e) {}
        player.start();
    }

    private boolean isConnectedToBeacon() {
        if (!wifiManager.isWifiEnabled()) return false;
        WifiInfo info = wifiManager.getConnectionInfo();
        return info.getSSID().replaceAll("^\"|\"$", "").equals(CAR_WIFI_BEACON_NAME);
    }

    private void enableAP() {
        try {
            wifiManager.setWifiEnabled(false);
            Method method = wifiManagerClass.getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            method.invoke(wifiManager, null, true);
        } catch (Exception e) {
        }
    }

    private boolean checkIfAPHasConnections(boolean checkIfReachable) {
        if (wifiManager.isWifiEnabled()) return false;
        try {
            Method getAPState = wifiManagerClass.getMethod("getWifiApState");
            if ((Integer)getAPState.invoke(wifiManager) != WIFI_AP_STATE_ENABLED) return false;
            Method getHotspotClients = wifiManagerClass.getMethod("getHotspotClients");
            List hotspotClients = (List)getHotspotClients.invoke(wifiManager);
            if (hotspotClients.isEmpty() || !checkIfReachable) return !hotspotClients.isEmpty();
            Runtime runtime = Runtime.getRuntime();
            Method getClientIp = wifiManagerClass.getMethod("getClientIp", String.class);
            for (Object client : hotspotClients) {
                String macAddress = (String)client.getClass().getField("deviceAddress").get(client);
                String ipAddress = (String)getClientIp.invoke(wifiManager, macAddress);
                if (runtime.exec("/system/bin/ping -c 1 -W 10 " + ipAddress).waitFor() == 0) {
                    return true;
                }
            }
        } catch (Exception e) {}
        return false;
    }

    private void disableAP() {
        try {
            Method getWifiApState = wifiManagerClass.getMethod("getWifiApState");
            Method setWifiApEnabled = wifiManagerClass.getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            for (;;) {
                setWifiApEnabled.invoke(wifiManager, null, false);
                int apState = (Integer)getWifiApState.invoke(wifiManager);
                if (apState == WIFI_AP_STATE_DISABLED || apState == WIFI_AP_STATE_FAILED) {
                    wifiManager.setWifiEnabled(true);
                    return;
                }
            }
        } catch (Exception e) {}
    }

    private void doLog(String message) {
        Log.d("TEMP", message);
    }


    Runnable updateState = new Runnable() {

        private Handler handler = new Handler();
        private long enableHotSpotTime = 0;
        private boolean isServing = false;

        @Override
        public void run() {

            handler.removeCallbacksAndMessages(null);
            if (!isServiceRunning) return;

            if (isServing) {
                if (!checkIfAPHasConnections(true)) {
                    isServing = false;
                    disableAP();
                    playSound(R.raw.contactoff);
                } else {
                    handler.postDelayed(this, UPDATE_STATE_INTERVAL_MS);
                }
            }

            else if (checkIfAPHasConnections(false)) {
                isServing = true;
                playSound(R.raw.contacton);
                handler.postDelayed(this, UPDATE_STATE_INTERVAL_MS);
            }

            else if (isConnectedToBeacon()) {
                enableAP();
                enableHotSpotTime = System.currentTimeMillis();
                handler.postDelayed(this, UPDATE_STATE_INTERVAL_MS);
            }

            else if (enableHotSpotTime != 0) {
                if ((System.currentTimeMillis() - enableHotSpotTime) > WAIT_FOR_CONNECTION_MS) {
                    enableHotSpotTime = 0;
                    disableAP();
                } else {
                    handler.postDelayed(this, UPDATE_STATE_INTERVAL_MS);
                }
            }


        }

    };

    private BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateState.run();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (!isServiceRunning) {
            isServiceRunning = true;
            context = getApplicationContext();
            wifiManager = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);

            List<WifiConfiguration> wifiNetworks = wifiManager.getConfiguredNetworks();
            if (wifiNetworks != null) for (WifiConfiguration network : wifiNetworks) {
                if (network.SSID.replaceAll("^\"|\"$", "").equals(CAR_WIFI_BEACON_NAME)) {
                    network.priority = 99999;
                    wifiManager.updateNetwork(network);
                }
            }


            IntentFilter filter = new IntentFilter();
            filter.addAction(WIFI_AP_STATE_CHANGED_ACTION);
            filter.addAction(WIFI_HOTSPOT_CLIENTS_CHANGED_ACTION);
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);

            filter.addAction("android.net.wifi.PPPOE_COMPLETED_ACTION");
            filter.addAction("android.net.wifi.WIFI_HOTSPOT_OVERLAP");
            filter.addAction("android.net.wifi.PPPOE_STATE_CHANGED");
            filter.addAction("android.net.wifi.supplicant.STATE_CHANGE");
            filter.addAction("android.net.wifi.supplicant.CONNECTION_CHANGE");
            filter.addAction("android.net.wifi.NETWORK_IDS_CHANGED");
            filter.addAction("android.net.wifi.LINK_CONFIGURATION_CHANGED");

            context.registerReceiver(wifiReceiver, filter);


//            if (wifiManager.isScanAlwaysAvailable()) {
//                doLog("YES_");
//            }



        }
    }

    private void doCleanup() {
        if (isServiceRunning) {
            context.unregisterReceiver(wifiReceiver);
            isServiceRunning = false;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        doCleanup();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        doCleanup();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        doCleanup();
        return false;
    }

}
