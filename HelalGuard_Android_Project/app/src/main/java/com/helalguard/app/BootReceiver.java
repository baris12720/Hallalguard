package com.helalguard.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.lang.reflect.Method;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            startPrayerService(context);
        }
    }

    public static void startPrayerService(Context context) {
        try {
            Intent serviceIntent = new Intent(context, PrayerForegroundService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                Method startFg = Context.class.getMethod("startForegroundService", Intent.class);
                startFg.invoke(context, serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception ignored) {
            try {
                context.startService(new Intent(context, PrayerForegroundService.class));
            } catch (Exception ignored2) {}
        }
    }
}
