package org.mcrf.anubis;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityEvent;

public final class LaunchObserver extends AccessibilityService {
    private static final String CLIENT = "org.mcrf.client";
    private static final String PERMISSION = CLIENT + ".permission.ANUBIS_EVENTS";

    private static final Uri WATCH = Uri.parse("content://" + CLIENT + ".anubis-watch/windows");
    private boolean registered;
    private final ContentObserver changes = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { refreshPackages(); }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            if (!registered) {
                getContentResolver().registerContentObserver(WATCH, false, changes);
                registered = true;
            }
        } catch (RuntimeException unavailable) {
            // Installation or replacement can temporarily remove the host provider.
        }
        refreshPackages();
    }

    private void refreshPackages() {
        var configuration = getServiceInfo();
        if (configuration == null) return;
        var packages = new ArrayList<String>();
        try (Cursor result = getContentResolver().query(WATCH, null, null, null, null)) {
            if (result != null) {
                while (result.moveToNext()) packages.add(result.getString(0));
            }
        } catch (RuntimeException unavailable) {
            packages.clear();
        }
        configuration.packageNames = packages.contains("*") ? null :
                packages.isEmpty() ? new String[] {getPackageName()} : packages.toArray(new String[0]);
        setServiceInfo(configuration);
    }

    @Override public void onDestroy() {
        if (registered) getContentResolver().unregisterContentObserver(changes);
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.getPackageName() == null || event.getClassName() == null ||
                getPackageManager().checkSignatures(getPackageName(), CLIENT) != PackageManager.SIGNATURE_MATCH) return;
        sendBroadcast(new Intent(CLIENT + ".ANUBIS_WINDOW")
                .setClassName(CLIENT, CLIENT + ".AnubisWindowReceiver")
                .putExtra("target", event.getPackageName().toString())
                .putExtra("window", event.getClassName().toString()), PERMISSION);
    }

    @Override public void onInterrupt() {}
}
