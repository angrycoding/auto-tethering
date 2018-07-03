package com.angrycoding.autotethering;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

public class AutoTetheringService  extends AccessibilityService {

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
    }

    private void doCleanup() {
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
