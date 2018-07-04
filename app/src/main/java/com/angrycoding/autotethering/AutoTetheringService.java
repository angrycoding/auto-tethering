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

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

public class AutoTetheringService  extends AccessibilityService {

    public int WIFI_AP_STATE_ENABLED = 13;
    public int WIFI_AP_STATE_FAILED = 14;

    private String WIFI_AP_STATE_CHANGED_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private String WIFI_HOTSPOT_CLIENTS_CHANGED_ACTION = "android.net.wifi.WIFI_HOTSPOT_CLIENTS_CHANGED";
    private String CAR_WIFI_BEACON_NAME = "mazda6-wifi-beacon";

    private Context context;
    private WifiManager wifiManager;
    private AudioManager audioManager;
    private boolean isRunning = false;
    private boolean somebodyThere = false;

    private boolean isConnectedToBeacon() {
        if (!wifiManager.isWifiEnabled()) return false;
        WifiInfo info = wifiManager.getConnectionInfo();
        return info.getSSID().replaceAll("^\"|\"$", "").equals(CAR_WIFI_BEACON_NAME);
    }

    private void enableAccessPoint() {
        try {
            wifiManager.setWifiEnabled(false);
            Method method = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            method.invoke(wifiManager, null, true);
        } catch (Exception e) {
        }
    }

    private int getHotSpotState() {
        try {
            Method method = wifiManager.getClass().getMethod("getWifiApState");
            return ((Integer) method.invoke(wifiManager));
        } catch (Exception e) {}
        return WIFI_AP_STATE_FAILED;
    }

    private void disableAccessPoint() {
        try {
            Method method = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            method.invoke(wifiManager, null, false);
            wifiManager.setWifiEnabled(true);
        } catch (Exception e) {
        }
    }

    private List getHotspotClients() {
        try {
            Method getHotspotClients = wifiManager.getClass().getMethod("getHotspotClients");
            return (List) getHotspotClients.invoke(wifiManager);
        } catch (Exception e) {}
        return Collections.emptyList();
    }

    private boolean doPing(String ipAddress) {
        Runtime runtime = Runtime.getRuntime();
        try {
            return runtime.exec("/system/bin/ping -c 1 -W 10 " + ipAddress).waitFor() == 0;
        } catch (Exception e) {
            Log.d("TEMP", e.toString());
        }
        return false;
    }

    private boolean isHotSpotClientsAlive(List hotspotClients) {
        if (!hotspotClients.isEmpty()) {
            try {
                Method getClientIp = wifiManager.getClass().getMethod("getClientIp", String.class);
                for (Object client : hotspotClients) {
                    String macAddress = (String) client.getClass().getField("deviceAddress").get(client);
                    //macAddress.equals(HEAD_UNIT_HW_ADDRESS)
                    String ipAddress = (String) getClientIp.invoke(wifiManager, macAddress);
                    if (doPing(ipAddress)) return true;
                }
            } catch (Exception e) {
            }
        }
        return false;
    }

    private void doLog(String message) {
        Log.d("TEMP", message);
    }

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

    Runnable myRunnable = new Runnable() {

        private Handler myHandler = new Handler();
        private long enableHotSpotTime = 0;

        @Override
        public void run() {

            myHandler.removeCallbacksAndMessages(null);
            if (!isRunning) return;

            List hotSpotClients = getHotspotClients();

            if (somebodyThere) {

                if (hotSpotClients.isEmpty() ||
                    wifiManager.isWifiEnabled() ||
                    getHotSpotState() != WIFI_AP_STATE_ENABLED ||
                    !isHotSpotClientsAlive(hotSpotClients)) {
                    somebodyThere = false;
                    disableAccessPoint();
                    playSound(R.raw.contactoff);
                } else {
                    myHandler.postDelayed(this, 10000);
                }
            }

            else if (!hotSpotClients.isEmpty()) {
                somebodyThere = true;
                playSound(R.raw.contacton);
                myHandler.postDelayed(this, 10000);
            }

            else if (isConnectedToBeacon()) {
                enableAccessPoint();
                enableHotSpotTime = System.currentTimeMillis();
                myHandler.postDelayed(this, 10000);
            }

            else if (enableHotSpotTime != 0) {
                if ((System.currentTimeMillis() - enableHotSpotTime) > 60000) {
                    enableHotSpotTime = 0;
                    disableAccessPoint();
                } else {
                    myHandler.postDelayed(this, 10000);
                }
            }


        }

    };

    private BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            myRunnable.run();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (!isRunning) {
            isRunning = true;
            context = getApplicationContext();
            wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);


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
        if (isRunning) {
            context.unregisterReceiver(wifiReceiver);
            isRunning = false;
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
