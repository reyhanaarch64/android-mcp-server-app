package mcp.android.phone;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

public final class PermissionUtil {
    private PermissionUtil() {
    }

    public static String[] runtimePermissions() {
        List<String> p = new ArrayList<String>();
        add(p, Manifest.permission.READ_CONTACTS);
        add(p, Manifest.permission.WRITE_CONTACTS);
        add(p, Manifest.permission.READ_SMS);
        add(p, Manifest.permission.RECEIVE_SMS);
        add(p, Manifest.permission.SEND_SMS);
        add(p, Manifest.permission.READ_CALL_LOG);
        add(p, Manifest.permission.WRITE_CALL_LOG);
        add(p, Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 33) add(p, "android.permission.READ_BASIC_PHONE_STATE");
        if (Build.VERSION.SDK_INT >= 26) add(p, Manifest.permission.READ_PHONE_NUMBERS);
        add(p, Manifest.permission.CALL_PHONE);
        add(p, Manifest.permission.READ_CALENDAR);
        add(p, Manifest.permission.WRITE_CALENDAR);
        add(p, Manifest.permission.CAMERA);
        add(p, Manifest.permission.RECORD_AUDIO);
        add(p, Manifest.permission.ACCESS_COARSE_LOCATION);
        add(p, Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 29) add(p, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            add(p, "android.permission.BLUETOOTH_CONNECT");
            add(p, "android.permission.BLUETOOTH_SCAN");
            add(p, "android.permission.BLUETOOTH_ADVERTISE");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(p, "android.permission.POST_NOTIFICATIONS");
            add(p, "android.permission.READ_MEDIA_IMAGES");
            add(p, "android.permission.READ_MEDIA_VIDEO");
            add(p, "android.permission.READ_MEDIA_AUDIO");
            add(p, "android.permission.NEARBY_WIFI_DEVICES");
        } else if (Build.VERSION.SDK_INT >= 23) {
            add(p, Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= 28) add(p, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return p.toArray(new String[p.size()]);
    }

    private static void add(List<String> list, String value) {
        if (value != null && !list.contains(value)) list.add(value);
    }

    public static boolean granted(Context c, String permission) {
        return Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestRuntime(Activity a, int code) {
        if (Build.VERSION.SDK_INT >= 23) a.requestPermissions(runtimePermissions(), code);
    }

    public static void openAllFiles(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
                i.setData(Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
            Intent i = new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        }
    }

    public static boolean hasAllFiles(Context c) {
        if (Build.VERSION.SDK_INT < 30) return true;
        try {
            java.lang.reflect.Method m = android.os.Environment.class.getMethod("isExternalStorageManager");
            Object result = m.invoke(null);
            return result instanceof Boolean && ((Boolean) result).booleanValue();
        } catch (Exception e) {
            return false;
        }
    }

    public static void openBattery(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        }
    }

    public static void openVivoAutostart(Context c) {
        Intent i = new Intent();
        i.setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            fallback.setData(Uri.parse("package:" + c.getPackageName()));
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(fallback);
        }
    }

    public static void openNotificationListener(Context c) {
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    public static void openAccessibility(Context c) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    public static void openUsage(Context c) {
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    public static void openOverlay(Context c) {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        i.setData(Uri.parse("package:" + c.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    public static void openWriteSettings(Context c) {
        Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
        i.setData(Uri.parse("package:" + c.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }
}
