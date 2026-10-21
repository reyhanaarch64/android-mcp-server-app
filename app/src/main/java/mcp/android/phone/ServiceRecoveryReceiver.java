package mcp.android.phone;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class ServiceRecoveryReceiver extends BroadcastReceiver {
    private static final String TAG = "PhoneToMCP";
    private static final int REQUEST_CODE = 9077;

    public void onReceive(Context context, Intent intent) {
        if (!ToolSettings.serverEnabled(context)) {
            cancel(context);
            return;
        }

        try {
            Intent serviceIntent = new Intent(context, PhoneMcpService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Gagal memulihkan service", t);
        }
    }

    public static void schedule(Context context, long delayMs) {
        if (delayMs < 0L) {
            cancel(context);
            return;
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, ServiceRecoveryReceiver.class);
        PendingIntent pi;
        if (Build.VERSION.SDK_INT >= 23) {
            pi = PendingIntent.getBroadcast(context, REQUEST_CODE, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
        } else {
            pi = PendingIntent.getBroadcast(context, REQUEST_CODE, i, PendingIntent.FLAG_UPDATE_CURRENT);
        }
        long when = System.currentTimeMillis() + Math.max(0L, delayMs);
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, when, pi);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Gagal menjadwalkan pemulihan service", t);
        }
    }

    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, ServiceRecoveryReceiver.class);
        PendingIntent pi;
        if (Build.VERSION.SDK_INT >= 23) {
            pi = PendingIntent.getBroadcast(context, REQUEST_CODE, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
        } else {
            pi = PendingIntent.getBroadcast(context, REQUEST_CODE, i, PendingIntent.FLAG_UPDATE_CURRENT);
        }
        try { am.cancel(pi); } catch (Throwable ignored) { }
        try { pi.cancel(); } catch (Throwable ignored) { }
    }
}
