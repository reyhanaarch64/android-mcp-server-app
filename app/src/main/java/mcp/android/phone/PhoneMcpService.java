package mcp.android.phone;

import android.Manifest;
import android.app.ActivityManager;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.media.Image;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.CalendarContract;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedList;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PhoneMcpService extends Service implements McpServer.McpToolHandler {
    private static final String TAG = "PhoneToMCP";
    private static final int NOTIFICATION_ID = 9009;
    private static final String CHANNEL_ID = "phone_mcp_server";
    public static final String ACTION_START_TUNNEL = "mcp.android.phone.action.START_TUNNEL";
    private static final long GREP_TUNNEL_TIMEOUT_MS = 30000L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private McpServer server;
    private Process cloudflaredProcess;
    private volatile String publicUrl;
    private MediaRecorder recorder;
    private File recordingFile;
    private final ArrayList<String> googleTtsQueue = new ArrayList<String>();
    private MediaPlayer googleTtsPlayer;
    private boolean googleTtsPlaying;
    private String googleTtsLang = "id";
    private volatile boolean serviceStopping;
    private Thread healthWatchdogThread;

    public void onCreate() {
        super.onCreate();
        serviceStopping = false;
        createChannel();
        startForegroundCompat(NOTIFICATION_ID, buildNotification("Server sedang berjalan"), false);
        server = new McpServer(this, this);
        try {
            server.start();
        } catch (Exception e) {
            Log.e(TAG, "MCP server gagal dimulai", e);
        }
        getSharedPreferences("server", MODE_PRIVATE).edit()
                .remove("public_url")
                .remove("tunnel_timeout")
                .remove("tunnel_error")
                .apply();
        if (ToolSettings.serverEnabled(this)) {
            startCloudflared();
            startHealthWatchdog();
        }
        ServiceRecoveryReceiver.cancel(this);
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        serviceStopping = false;
        if (server == null) {
            server = new McpServer(this, this);
        }
        try {
            if (!server.isRunning()) server.start();
        } catch (Exception e) {
            Log.e(TAG, "MCP server gagal dipulihkan", e);
        }
        if (ToolSettings.serverEnabled(this)) {
            startHealthWatchdog();
            if (!isCloudflaredRunning()) startCloudflared();
        }
        ServiceRecoveryReceiver.cancel(this);
        return ToolSettings.serverEnabled(this) ? START_STICKY : START_NOT_STICKY;
    }

    public void onTaskRemoved(Intent rootIntent) {
        if (ToolSettings.serverEnabled(this) && !serviceStopping) {
            ServiceRecoveryReceiver.schedule(this, 3000L);
        }
        super.onTaskRemoved(rootIntent);
    }

    public void onDestroy() {
        serviceStopping = true;
        stopHealthWatchdog();
        ServiceRecoveryReceiver.schedule(this, ToolSettings.serverEnabled(this) ? 3000L : -1L);
        stopCloudflared();
        stopRecordingInternal();
        stopTts();
        if (server != null) server.stop();
        server = null;
        try { stopForeground(true); } catch (Exception ignored) { }
        super.onDestroy();
    }

    public android.os.IBinder onBind(Intent intent) {
        return null;
    }

    public synchronized void startCloudflared() {
        if (cloudflaredProcess != null) return;

        final File binary = getCloudflaredBinary();
        if (binary == null || !binary.exists()) {
            getSharedPreferences("server", MODE_PRIVATE).edit()
                    .putBoolean("tunnel_timeout", true)
                    .putString("tunnel_error", "Binary cloudflared tidak tersedia.")
                    .apply();
            return;
        }

        if (!binary.canExecute()) {
            getSharedPreferences("server", MODE_PRIVATE).edit()
                    .putBoolean("tunnel_timeout", true)
                    .putString("tunnel_error", "Binary cloudflared tidak dapat dieksekusi.")
                    .apply();
            return;
        }

        getSharedPreferences("server", MODE_PRIVATE).edit()
                .remove("public_url").remove("tunnel_timeout").remove("tunnel_error").apply();

        try {
            String command = shellQuote(binary.getAbsolutePath())
                    + " tunnel --url http://127.0.0.1:" + McpServer.PORT
                    + " 2>&1 | grep --line-buffered -o 'https://[a-zA-Z0-9.-]*\\.trycloudflare\\.com'";
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
            pb.redirectErrorStream(true);
            cloudflaredProcess = pb.start();
            final Process p = cloudflaredProcess;

            Thread reader = new Thread(new Runnable() {
                public void run() {
                    java.io.BufferedReader br = null;
                    try {
                        br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                        String line;
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("https://[a-zA-Z0-9.-]*\\.trycloudflare\\.com");
                        while ((line = br.readLine()) != null) {
                            java.util.regex.Matcher m = pattern.matcher(line);
                            if (m.find()) {
                                String url = m.group() + "/mcp";
                                publicUrl = url;
                                getSharedPreferences("server", MODE_PRIVATE).edit()
                                        .putString("public_url", url)
                                        .remove("tunnel_timeout")
                                        .remove("tunnel_error")
                                        .apply();
                                updateNotification("Tunnel aktif: " + url);
                            }
                        }
                    } catch (Exception e) {
                        getSharedPreferences("server", MODE_PRIVATE).edit()
                                .putString("tunnel_error", safeMessage(e)).apply();
                        Log.e(TAG, "cloudflared output", e);
                    } finally {
                        try { if (br != null) br.close(); } catch (Exception ignored) { }
                        synchronized (PhoneMcpService.this) {
                            if (cloudflaredProcess == p) cloudflaredProcess = null;
                        }
                        if (!serviceStopping && ToolSettings.serverEnabled(PhoneMcpService.this)) {
                            mainHandler.postDelayed(new Runnable() {
                                public void run() {
                                    if (!serviceStopping && ToolSettings.serverEnabled(PhoneMcpService.this) && !isCloudflaredRunning()) {
                                        startCloudflared();
                                    }
                                }
                            }, 3000L);
                        }
                    }
                }
            }, "cloudflared-grep-reader");
            reader.setDaemon(true);
            reader.start();

            Thread timeoutThread = new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(GREP_TUNNEL_TIMEOUT_MS); } catch (InterruptedException ignored) { return; }
                    if (cloudflaredProcess == p &&
                            getSharedPreferences("server", MODE_PRIVATE).getString("public_url", null) == null) {
                        getSharedPreferences("server", MODE_PRIVATE).edit()
                                .putBoolean("tunnel_timeout", true).apply();
                    }
                }
            }, "cloudflared-grep-timeout");
            timeoutThread.setDaemon(true);
            timeoutThread.start();
        } catch (Exception e) {
            cloudflaredProcess = null;
            getSharedPreferences("server", MODE_PRIVATE).edit()
                    .putBoolean("tunnel_timeout", true)
                    .putString("tunnel_error", safeMessage(e)).apply();
            Log.e(TAG, "cloudflared gagal", e);
            if (!serviceStopping && ToolSettings.serverEnabled(this)) {
                mainHandler.postDelayed(new Runnable() {
                    public void run() {
                        if (!serviceStopping && ToolSettings.serverEnabled(PhoneMcpService.this) && !isCloudflaredRunning()) {
                            startCloudflared();
                        }
                    }
                }, 5000L);
            }
        }
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private File getCloudflaredBinary() {
        try {
            String abi = Build.CPU_ABI;
            if (Build.VERSION.SDK_INT >= 21) {
                String[] abis = Build.SUPPORTED_ABIS;
                if (abis != null && abis.length > 0) abi = abis[0];
            }
            if (abi == null || abi.indexOf("arm64") < 0) {
                Log.e(TAG, "cloudflared memerlukan arm64-v8a, ABI perangkat: " + abi);
                return null;
            }
            String nativeDir = getApplicationInfo().nativeLibraryDir;
            if (nativeDir == null) return null;
            File binary = new File(nativeDir, "libcloudflared.so");
            return binary.exists() && binary.isFile() && binary.canExecute() ? binary : null;
        } catch (Exception e) {
            Log.e(TAG, "gagal mendapatkan nativeLibraryDir", e);
            return null;
        }
    }

    private synchronized void stopCloudflaredProcessOnly() {
        Process p = cloudflaredProcess;
        cloudflaredProcess = null;
        if (p != null) {
            try { p.destroy(); } catch (Exception ignored) { }
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    java.lang.reflect.Method m = Process.class.getMethod("destroyForcibly");
                    m.invoke(p);
                }
            } catch (Exception ignored) { }
        }
    }

    public synchronized void stopCloudflared() {
        stopCloudflaredProcessOnly();
        publicUrl = null;
        getSharedPreferences("server", MODE_PRIVATE).edit().remove("public_url").remove("tunnel_timeout").apply();
    }

    public boolean isCloudflaredRunning() {
        Process p = cloudflaredProcess;
        if (p == null) return false;
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    private static String safeMessage(Exception e) {
        if (e == null) return "Kesalahan tidak diketahui";
        String m = e.getMessage();
        if (m == null || m.length() == 0) return e.toString();
        return m;
    }

    private synchronized void startHealthWatchdog() {
        if (healthWatchdogThread != null && healthWatchdogThread.isAlive()) return;
        healthWatchdogThread = new Thread(new Runnable() {
            public void run() {
                while (!serviceStopping && ToolSettings.serverEnabled(PhoneMcpService.this)) {
                    try {
                        if (server == null) {
                            server = new McpServer(PhoneMcpService.this, PhoneMcpService.this);
                        }
                        if (!server.isRunning()) {
                            try {
                                server.start();
                                updateNotification("Server MCP dipulihkan");
                            } catch (Exception e) {
                                Log.e(TAG, "Watchdog gagal memulihkan MCP server", e);
                            }
                        }
                        Thread.sleep(5000L);
                    } catch (InterruptedException e) {
                        break;
                    } catch (Throwable t) {
                        Log.e(TAG, "Watchdog", t);
                        try { Thread.sleep(2000L); } catch (InterruptedException e) { break; }
                    }
                }
            }
        }, "phone-mcp-watchdog");
        healthWatchdogThread.setDaemon(true);
        healthWatchdogThread.start();
    }

    private synchronized void stopHealthWatchdog() {
        Thread t = healthWatchdogThread;
        healthWatchdogThread = null;
        if (t != null) t.interrupt();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Phone To MCP Server", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Status server MCP Phone To MCP");
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi;
        if (Build.VERSION.SDK_INT >= 23) pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        else pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("Phone To MCP")
                .setContentText(text)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);
        return b.build();
    }

    private void startForegroundCompat(int id, Notification notification, boolean locationLike) {
        if (Build.VERSION.SDK_INT >= 29) {
            int types = foregroundType("FOREGROUND_SERVICE_TYPE_DATA_SYNC");
            if (types != 0 && invokeTypedStartForeground(id, notification, types)) return;
        }
        startForeground(id, notification);
    }

    private int foregroundType(String name) {
        try {
            java.lang.reflect.Field f = ServiceInfo.class.getField(name);
            return f.getInt(null);
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean invokeTypedStartForeground(int id, Notification notification, int types) {
        try {
            java.lang.reflect.Method m = Service.class.getMethod("startForeground", Integer.TYPE, Notification.class, Integer.TYPE);
            m.invoke(this, Integer.valueOf(id), notification, Integer.valueOf(types));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "typed foreground gagal, fallback", e);
            return false;
        }
    }

    private void ensureMicrophoneForeground() {
        if (Build.VERSION.SDK_INT >= 29 && PermissionUtil.granted(this, Manifest.permission.RECORD_AUDIO)) {
            try {
                int types = foregroundType("FOREGROUND_SERVICE_TYPE_DATA_SYNC") | foregroundType("FOREGROUND_SERVICE_TYPE_MICROPHONE");
                if (!invokeTypedStartForeground(NOTIFICATION_ID, buildNotification("Mikrofon sedang digunakan"), types)) {
                    startForeground(NOTIFICATION_ID, buildNotification("Mikrofon sedang digunakan"));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void ensureLocationForeground() {
        if (Build.VERSION.SDK_INT >= 29 && (PermissionUtil.granted(this, Manifest.permission.ACCESS_FINE_LOCATION) || PermissionUtil.granted(this, Manifest.permission.ACCESS_COARSE_LOCATION))) {
            try {
                int types = foregroundType("FOREGROUND_SERVICE_TYPE_DATA_SYNC") | foregroundType("FOREGROUND_SERVICE_TYPE_LOCATION");
                if (!invokeTypedStartForeground(NOTIFICATION_ID, buildNotification("Akses lokasi digunakan"), types)) {
                    startForeground(NOTIFICATION_ID, buildNotification("Akses lokasi digunakan"));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    public JSONObject callTool(String name, JSONObject a) throws Exception {
        ToolDef def = ToolRegistry.get(name);
        if (def == null) return toolError("tool tidak ditemukan: " + name);
        if (!ToolSettings.isEnabled(this, def)) return toolError("tool dinonaktifkan di pengaturan Phone To MCP: " + name);
        try {
            JSONObject value = execute(name, a);
            JSONObject r = new JSONObject();
            JSONArray content = new JSONArray();
            String imageData = value == null ? null : value.optString("__mcp_image_base64", null);
            String imageMime = value == null ? null : value.optString("__mcp_image_mime", "image/png");
            if (value != null && value.has("__mcp_image_base64")) value.remove("__mcp_image_base64");
            if (value != null && value.has("__mcp_image_mime")) value.remove("__mcp_image_mime");
            JSONObject textItem = new JSONObject();
            textItem.put("type", "text");
            textItem.put("text", value == null ? "ok" : value.toString());
            content.put(textItem);
            if (imageData != null && imageData.length() > 0) {
                JSONObject imageItem = new JSONObject();
                imageItem.put("type", "image");
                imageItem.put("data", imageData);
                imageItem.put("mimeType", imageMime);
                content.put(imageItem);
            }
            r.put("content", content);
            r.put("isError", false);
            if (value != null) r.put("structuredContent", value);
            return r;
        } catch (Exception e) {
            Log.e(TAG, "tool " + name, e);
            return toolError(safe(e));
        }
    }

    private JSONObject toolError(String message) {
        JSONObject r = new JSONObject();
        try {
            JSONArray content = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("type", "text");
            item.put("text", message);
            content.put(item);
            r.put("content", content);
            r.put("isError", true);
        } catch (Exception ignored) {
        }
        return r;
    }

    private JSONObject execute(String n, JSONObject a) throws Exception {
        if (n.startsWith("device.") || n.equals("spec.view")) return device(n.equals("spec.view") ? "device.spec" : n, a);
        if (n.equals("app.current")) return currentApps();
        if (n.equals("screen.capture") || n.equals("screen.capcure")) return screenCapture(a);
        if (n.equals("logcat.read")) return logcatRead(a);
        if (n.equals("tiktok.download")) return tiktokDownload(a);
        if (n.equals("server.status")) return serverStatus();
        if (n.equals("access.permissions")) return permissionsStatus();
        if (n.equals("mcp.tools_enabled")) return enabledTools();
        if (n.equals("battery.info")) return battery();
        if (n.equals("display.info")) return display();
        if (n.startsWith("sensor.")) return sensor(n, a);
        if (n.equals("storage.info")) return storage();
        if (n.equals("network.info")) return network();
        if (n.equals("time.now")) return obj("iso", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new java.util.Date()), "epochMillis", System.currentTimeMillis());
        if (n.startsWith("file.") || n.startsWith("zip.") || n.startsWith("editor.")) return files(n, a);
        if (n.startsWith("contacts.")) return contacts(n, a);
        if (n.startsWith("sms.")) return sms(n, a);
        if (n.startsWith("calllog.")) return callLog(n, a);
        if (n.startsWith("phone.")) return phone(n, a);
        if (n.startsWith("calendar.")) return calendar(n, a);
        if (n.startsWith("media.")) return media(n, a);
        if (n.equals("camera.open")) return cameraOpen();
        if (n.startsWith("audio.")) return audio(n, a);
        if (n.startsWith("tts.")) return tts(n, a);
        if (n.equals("vibrate")) return vibrate(a);
        if (n.equals("flashlight.set")) return flashlight(a);
        if (n.equals("location.get")) return location();
        if (n.startsWith("wifi.")) return wifi(n, a);
        if (n.startsWith("bluetooth.")) return bluetooth(n, a);
        if (n.startsWith("app.") || n.equals("pm.read")) return apps(n, a);
        if (n.equals("share.text")) return shareText(a);
        if (n.equals("share.file")) return shareFile(a);
        if (n.equals("download.url")) return download(a);
        if (n.startsWith("notification")) return notifications(n, a);
        if (n.equals("usage.stats")) return usage(a);
        if (n.startsWith("accessibility.")) return accessibility(n, a);
        if (n.equals("display.brightness_get")) return brightnessGet();
        if (n.equals("display.brightness_set")) return brightnessSet(a);
        if (n.startsWith("settings.")) return openSetting(n);
        if (n.equals("access.special_status")) return specialStatus();
        if (n.equals("toast.show")) return toast(a);
        if (n.equals("url.open")) return openUrl(a);
        if (n.equals("clipboard.read")) return clipboardRead();
        if (n.equals("clipboard.write")) return clipboardWrite(a);
        return new JSONObject();
    }

    private JSONObject fileReadBase64(JSONObject a) throws Exception {
        File f = FileOps.resolve(this, a.getString("root"), a.getString("path"));
        if (!f.isFile()) throw new IllegalArgumentException("bukan file");
        if (f.length() > FileOps.MAX_BASE64_BYTES) throw new IllegalArgumentException("file melebihi batas 16 MB");
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) f.length());
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return obj("path", a.getString("path"), "bytes", f.length(), "base64", android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP));
    }

    private JSONObject currentApps() throws Exception {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
        JSONArray arr = new JSONArray();
        PackageManager pm = getPackageManager();
        if (list != null) {
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            for (ActivityManager.RunningAppProcessInfo p : list) {
                if (p == null || p.pkgList == null) continue;
                int importance = p.importance;
                String state;
                if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) state = "foreground";
                else if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) state = "visible";
                else if (importance <= 125) state = "foreground_service";
                else state = "background";
                for (String pkg : p.pkgList) {
                    if (pkg == null || seen.contains(pkg)) continue;
                    seen.add(pkg);
                    JSONObject o = new JSONObject();
                    o.put("package", pkg);
                    o.put("process", p.processName);
                    o.put("pid", p.pid);
                    o.put("importance", importance);
                    o.put("state", state);
                    try { o.put("name", pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); } catch (Exception ignored) { }
                    arr.put(o);
                }
            }
        }
        JSONObject result = obj("apps", arr, "count", arr.length(), "note", "Android dapat membatasi daftar proses yang terlihat oleh aplikasi pihak ketiga.");
        if (usageAccessEnabled() && Build.VERSION.SDK_INT >= 21) {
            try {
                android.app.usage.UsageStatsManager um = (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
                long now = System.currentTimeMillis();
                List<android.app.usage.UsageStats> stats = um.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 600000L, now);
                android.app.usage.UsageStats latest = null;
                if (stats != null) for (android.app.usage.UsageStats u : stats) if (u != null && (latest == null || u.getLastTimeUsed() > latest.getLastTimeUsed())) latest = u;
                if (latest != null) result.put("foregroundPackageFromUsage", latest.getPackageName());
            } catch (Exception ignored) { }
        }
        return result;
    }

    private JSONObject screenCapture(JSONObject a) throws Exception {
        int timer = Math.max(0, Math.min(30, a.optInt("timer", 0)));
        ensureScreenCaptureForeground();
        return ScreenCaptureManager.capture(this, timer * 1000L);
    }

    private void ensureScreenCaptureForeground() {
        if (Build.VERSION.SDK_INT >= 29) {
            int types = foregroundType("FOREGROUND_SERVICE_TYPE_DATA_SYNC") | foregroundType("FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION");
            if (!invokeTypedStartForeground(NOTIFICATION_ID, buildNotification("Tangkapan layar sedang digunakan"), types)) {
                startForeground(NOTIFICATION_ID, buildNotification("Tangkapan layar sedang digunakan"));
            }
        }
    }

    private JSONObject logcatRead(JSONObject a) throws Exception {
        int lines = Math.max(1, Math.min(2000, a.optInt("lines", 300)));
        String filter = a.optString("filter", "").trim();
        Process p = new ProcessBuilder("/system/bin/logcat", "-d", "-t", String.valueOf(lines)).redirectErrorStream(true).start();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        StringBuilder out = new StringBuilder(); String line; int count = 0;
        while ((line = br.readLine()) != null) {
            if (filter.length() == 0 || line.toLowerCase(Locale.US).contains(filter.toLowerCase(Locale.US))) {
                out.append(line).append('\n'); count++;
            }
        }
        br.close();
        try { p.waitFor(3, TimeUnit.SECONDS); } catch (Exception ignored) { }
        return obj("lines", count, "output", out.toString(), "filter", filter, "note", "Android dapat membatasi log sistem yang terlihat oleh aplikasi pihak ketiga.");
    }

    private JSONObject tiktokDownload(JSONObject a) throws Exception {
        String source = a.getString("url").trim();
        if (!(source.startsWith("https://") || source.startsWith("http://")) || source.indexOf("tiktok.com") < 0) throw new IllegalArgumentException("URL TikTok tidak valid");
        if (Build.VERSION.SDK_INT >= 30 && !PermissionUtil.hasAllFiles(this)) throw new SecurityException("Akses semua file belum diaktifkan");
        String api = "https://www.tikwm.com/api/?url=" + URLEncoder.encode(source, "UTF-8") + "&hd=1";
        JSONObject response = new JSONObject(httpGet(api));
        JSONObject data = response.optJSONObject("data");
        if (data == null) throw new IllegalStateException("TikWM tidak mengembalikan data video");
        String id = data.optString("id", "").trim();
        if (id.length() == 0) id = extractTikTokId(source);
        if (id.length() == 0) id = String.valueOf(System.currentTimeMillis());
        File root = new File(Environment.getExternalStorageDirectory(), "RHDOWN/Tiktok/" + sanitizeFileName(id));
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("gagal membuat direktori TikTok");
        ArrayList<String> paths = new ArrayList<String>();
        ArrayList<String> mimes = new ArrayList<String>();
        String videoUrl = normalizeRemoteUrl(data.optString("play", ""));
        if (videoUrl.length() > 0) {
            File out = new File(root, id + ".mp4");
            downloadHttpFile(videoUrl, out);
            paths.add(out.getAbsolutePath()); mimes.add("video/mp4");
        }
        JSONObject music = data.optJSONObject("music_info");
        if (music != null) {
            String audioUrl = normalizeRemoteUrl(music.optString("play", ""));
            if (audioUrl.length() > 0) {
                File out = new File(root, id + ".mp3");
                downloadHttpFile(audioUrl, out);
                paths.add(out.getAbsolutePath()); mimes.add("audio/mpeg");
            }
        }
        JSONArray images = data.optJSONArray("images");
        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                String imageUrl = normalizeRemoteUrl(images.optString(i, ""));
                if (imageUrl.length() == 0) continue;
                File out = new File(root, "image_" + (i + 1) + ".jpg");
                downloadHttpFile(imageUrl, out);
                paths.add(out.getAbsolutePath()); mimes.add("image/jpeg");
            }
        }
        if (paths.size() == 0) throw new IllegalStateException("media TikTok tidak ditemukan");
        String[] pa = paths.toArray(new String[paths.size()]);
        String[] ma = mimes.toArray(new String[mimes.size()]);
        MediaScannerConnection.scanFile(this, pa, ma, null);
        return obj("downloaded", true, "id", id, "directory", root.getAbsolutePath(), "files", new JSONArray(paths), "title", data.optString("title", ""));
    }

    private String httpGet(String target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) PhoneToMCP");
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        if (in == null) throw new IllegalStateException("respons HTTP kosong: " + code);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close(); c.disconnect();
        String result = new String(out.toByteArray(), "UTF-8");
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + result);
        return result;
    }

    private void downloadHttpFile(String target, File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(60000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) PhoneToMCP");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) { c.disconnect(); throw new IllegalStateException("HTTP download " + code); }
        InputStream in = c.getInputStream(); FileOutputStream outStream = new FileOutputStream(out); byte[] buf = new byte[16384]; int n;
        try { while ((n = in.read(buf)) != -1) outStream.write(buf, 0, n); outStream.flush(); }
        finally { try { in.close(); } catch (Exception ignored) { } try { outStream.close(); } catch (Exception ignored) { } c.disconnect(); }
    }

    private String normalizeRemoteUrl(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.startsWith("//")) return "https:" + v;
        if (v.startsWith("/")) return "https://www.tikwm.com" + v;
        return v;
    }

    private String extractTikTokId(String source) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/video/(\\d+)").matcher(source);
        if (m.find()) return m.group(1);
        return "";
    }

    private String sanitizeFileName(String value) {
        String s = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return s.length() == 0 ? "unknown" : s;
    }

    private JSONObject device(String n, JSONObject a) throws Exception {
        if ("device.spec".equals(n)) {
            String section = a.optString("section", "all").toLowerCase(Locale.US);
            JSONObject all = new JSONObject();
            if ("all".equals(section) || "model".equals(section)) {
                all.put("manufacturer", Build.MANUFACTURER);
                all.put("brand", Build.BRAND);
                all.put("model", Build.MODEL);
                all.put("device", Build.DEVICE);
                all.put("product", Build.PRODUCT);
                all.put("hardware", Build.HARDWARE);
                all.put("releaseYear", JSONObject.NULL);
                all.put("releaseYearNote", "tahun peluncuran model tidak disediakan oleh API Android standar");
                all.put("osBuildTime", Build.TIME);
            }
            if ("all".equals(section) || "android".equals(section)) {
                all.put("androidVersion", Build.VERSION.RELEASE);
                all.put("apiLevel", Build.VERSION.SDK_INT);
                all.put("securityPatch", Build.VERSION.SDK_INT >= 23 ? Build.VERSION.SECURITY_PATCH : "n/a");
                all.put("fingerprint", Build.FINGERPRINT);
            }
            if ("all".equals(section) || "storage".equals(section)) all.put("storage", storage());
            if ("all".equals(section) || "memory".equals(section)) all.put("memory", memory());
            if ("all".equals(section) || "display".equals(section)) all.put("display", display());
            if ("all".equals(section) || "cpu".equals(section)) all.put("cpu", cpu());
            if ("all".equals(section) || "battery".equals(section)) all.put("battery", battery());
            if ("all".equals(section) || "network".equals(section)) all.put("network", network());
            return all;
        }
        return obj("device", deviceInfo());
    }

    private JSONObject serverStatus() throws Exception {
        JSONObject o = new JSONObject();
        o.put("running", server != null && server.isRunning());
        o.put("port", McpServer.PORT);
        o.put("localUrl", "localhost:" + McpServer.PORT + "/mcp");
        String u = getSharedPreferences("server", MODE_PRIVATE).getString("public_url", null);
        o.put("publicUrl", u == null ? JSONObject.NULL : u);
        o.put("authEnabled", ToolSettings.authEnabled(this));
        return o;
    }

    private JSONObject permissionsStatus() throws Exception {
        JSONObject o = new JSONObject();
        String[] ps = PermissionUtil.runtimePermissions();
        for (String p : ps) o.put(p, PermissionUtil.granted(this, p));
        o.put("allFiles", PermissionUtil.hasAllFiles(this));
        o.put("notificationListener", PhoneNotificationListener.INSTANCE != null);
        o.put("accessibility", PhoneAccessibilityService.INSTANCE != null);
        o.put("usageAccess", usageAccessEnabled());
        o.put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this));
        o.put("writeSettings", Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this));
        return o;
    }

    private JSONObject enabledTools() throws Exception {
        JSONArray a = new JSONArray();
        for (ToolDef t : ToolRegistry.all()) if (ToolSettings.isEnabled(this, t)) a.put(t.name);
        return obj("tools", a, "count", a.length());
    }

    private JSONObject deviceInfo() throws Exception {
        JSONObject o = new JSONObject();
        o.put("manufacturer", Build.MANUFACTURER);
        o.put("brand", Build.BRAND);
        o.put("model", Build.MODEL);
        o.put("android", Build.VERSION.RELEASE);
        o.put("api", Build.VERSION.SDK_INT);
        o.put("appPackage", getPackageName());
        o.put("appFiles", getFilesDir().getAbsolutePath());
        return o;
    }

    private JSONObject memory() throws Exception {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        JSONObject o = new JSONObject();
        o.put("totalBytes", mi.totalMem);
        o.put("availableBytes", mi.availMem);
        o.put("usedBytes", Math.max(0L, mi.totalMem - mi.availMem));
        o.put("lowMemory", mi.lowMemory);
        o.put("usedPercent", mi.totalMem > 0 ? (100.0 * (mi.totalMem - mi.availMem) / mi.totalMem) : 0);
        return o;
    }

    private JSONObject cpu() throws Exception {
        JSONObject o = new JSONObject();
        o.put("cores", Runtime.getRuntime().availableProcessors());
        o.put("abi", Build.VERSION.SDK_INT >= 21 ? join(Build.SUPPORTED_ABIS) : Build.CPU_ABI);
        o.put("abi2", Build.VERSION.SDK_INT >= 21 ? join(Build.SUPPORTED_32_BIT_ABIS) : Build.CPU_ABI2);
        o.put("osArch", System.getProperty("os.arch"));
        return o;
    }

    private JSONObject battery() throws Exception {
        Intent i = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        JSONObject o = new JSONObject();
        if (i != null) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            o.put("percent", scale > 0 ? (100.0 * level / scale) : -1);
            o.put("status", i.getIntExtra(BatteryManager.EXTRA_STATUS, -1));
            o.put("health", i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1));
            o.put("temperatureC", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
            o.put("voltageMv", i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0));
            o.put("plugged", i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));
        }
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (Build.VERSION.SDK_INT >= 21) {
            try { o.put("capacity", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)); } catch (Exception ignored) { }
            try { o.put("currentNowUa", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)); } catch (Exception ignored) { }
        }
        return o;
    }

    private JSONObject display() throws Exception {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        d.getMetrics(dm);
        JSONObject o = new JSONObject();
        o.put("widthPx", dm.widthPixels);
        o.put("heightPx", dm.heightPixels);
        o.put("density", dm.density);
        o.put("densityDpi", dm.densityDpi);
        o.put("xdpi", dm.xdpi);
        o.put("ydpi", dm.ydpi);
        o.put("refreshRateHz", d.getRefreshRate());
        o.put("fps", d.getRefreshRate());
        o.put("fpsNote", "nilai fps di sini adalah refresh rate display yang dapat dibaca aplikasi, bukan counter FPS GPU global");
        return o;
    }

    private JSONObject storage() throws Exception {
        StatFs f = new StatFs(Environment.getDataDirectory().getPath());
        long total = Build.VERSION.SDK_INT >= 18 ? f.getTotalBytes() : ((long) f.getBlockCount()) * f.getBlockSize();
        long free = Build.VERSION.SDK_INT >= 18 ? f.getAvailableBytes() : ((long) f.getAvailableBlocks()) * f.getBlockSize();
        StatFs e = new StatFs(Environment.getExternalStorageDirectory().getPath());
        long st = Build.VERSION.SDK_INT >= 18 ? e.getTotalBytes() : ((long) e.getBlockCount()) * e.getBlockSize();
        long sf = Build.VERSION.SDK_INT >= 18 ? e.getAvailableBytes() : ((long) e.getAvailableBlocks()) * e.getBlockSize();
        JSONObject o = new JSONObject();
        o.put("internalDataTotalBytes", total);
        o.put("internalDataFreeBytes", free);
        o.put("sharedStorageTotalBytes", st);
        o.put("sharedStorageFreeBytes", sf);
        o.put("allFilesAccess", PermissionUtil.hasAllFiles(this));
        return o;
    }

    private JSONObject network() throws Exception {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        JSONObject o = new JSONObject();
        if (Build.VERSION.SDK_INT >= 23) {
            Network n = cm.getActiveNetwork();
            if (n != null) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null) {
                    o.put("validated", c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                    o.put("wifi", c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                    o.put("cellular", c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                    o.put("vpn", c.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                    o.put("ethernet", c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
                }
            }
        }
        o.put("publicTunnel", publicUrl == null ? JSONObject.NULL : publicUrl);
        return o;
    }

    private JSONObject files(String n, JSONObject a) throws Exception {
        if (n.equals("file.read_base64")) return fileReadBase64(a);
        if (n.equals("file.write_base64")) return FileOps.write(this, a.getString("root"), a.getString("path"), a.getString("base64"), "base64", false, false);
        if (n.equals("file.read") || n.equals("editor.read_file")) return FileOps.read(this, a.getString("root"), a.getString("path"), a.optInt("maxBytes", 2 * 1024 * 1024));
        if (n.equals("file.write")) return FileOps.write(this, a.getString("root"), a.getString("path"), a.getString("content"), a.optString("encoding", "utf8"), false, false);
        if (n.equals("file.create") || n.equals("editor.create_file")) return FileOps.write(this, a.getString("root"), a.getString("path"), a.optString("content", ""), "utf8", true, false);
        if (n.equals("file.append")) return FileOps.write(this, a.getString("root"), a.getString("path"), a.getString("content"), "utf8", false, true);
        if (n.equals("file.delete")) { FileOps.deleteRecursive(FileOps.resolve(this, a.getString("root"), a.getString("path"))); return obj("deleted", true); }
        if (n.equals("file.exists")) return obj("exists", FileOps.resolve(this, a.getString("root"), a.getString("path")).exists());
        if (n.equals("file.list")) return FileOps.list(this, a.getString("root"), a.getString("path"), a.optBoolean("recursive", false), a.optInt("limit", 5000));
        if (n.equals("file.mkdir")) return obj("created", FileOps.resolve(this, a.getString("root"), a.getString("path")).mkdirs());
        if (n.equals("file.copy")) { FileOps.copyRecursive(FileOps.resolve(this, a.getString("root"), a.getString("from")), FileOps.resolve(this, a.getString("root"), a.getString("to"))); return obj("copied", true); }
        if (n.equals("file.move")) { File from = FileOps.resolve(this, a.getString("root"), a.getString("from")); File to = FileOps.resolve(this, a.getString("root"), a.getString("to")); if (!from.renameTo(to)) { FileOps.copyRecursive(from, to); FileOps.deleteRecursive(from); } return obj("moved", true); }
        if (n.equals("zip.create")) return FileOps.zip(this, a.getString("root"), a.getString("source"), a.getString("zip"));
        if (n.equals("zip.extract")) return FileOps.unzip(this, a.getString("root"), a.getString("zip"), a.getString("destination"));
        if (n.equals("editor.edit_file")) {
            JSONObject r = FileOps.read(this, a.getString("root"), a.getString("path"), a.optInt("maxBytes", 8 * 1024 * 1024));
            String before = r.getString("content");
            String after = FileOps.edit(before, a.getString("find"), a.optString("replace", ""), a.optBoolean("regex", false));
            FileOps.write(this, a.getString("root"), a.getString("path"), after, "utf8", false, false);
            return obj("changed", !before.equals(after), "bytes", after.getBytes("UTF-8").length);
        }
        return new JSONObject();
    }

    private JSONObject contacts(String n, JSONObject a) throws Exception {
        ContentResolver cr = getContentResolver();
        if (n.equals("contacts.read") || n.equals("contacts.list")) {
            String query = a.optString("query", "");
            int limit = a.optInt("limit", 10000);
            Cursor c = cr.query(ContactsContract.Contacts.CONTENT_URI, new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.HAS_PHONE_NUMBER}, TextUtils.isEmpty(query) ? null : ContactsContract.Contacts.DISPLAY_NAME + " LIKE ?", TextUtils.isEmpty(query) ? null : new String[]{"%" + query + "%"}, ContactsContract.Contacts.DISPLAY_NAME + " COLLATE NOCASE ASC");
            JSONArray out = new JSONArray();
            if (c != null) {
                while (c.moveToNext() && out.length() < limit) {
                    String id = c.getString(0);
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("name", c.getString(1));
                    Cursor p = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID}, ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?", new String[]{id}, null);
                    JSONArray phones = new JSONArray();
                    String raw = null;
                    if (p != null) { while (p.moveToNext()) { phones.put(p.getString(0)); raw = p.getString(1); } p.close(); }
                    o.put("phones", phones);
                    if (raw != null) o.put("rawContactId", raw);
                    out.put(o);
                }
                c.close();
            }
            JSONObject r = new JSONObject(); r.put("contacts", out); return r;
        }
        if (n.equals("contacts.create")) {
            ArrayList<ContentValues> values = new ArrayList<ContentValues>();
            ContentValues raw = new ContentValues();
            Uri rawUri = cr.insert(ContactsContract.RawContacts.CONTENT_URI, raw);
            if (rawUri == null) throw new IllegalStateException("gagal membuat raw contact");
            long rawId = android.content.ContentUris.parseId(rawUri);
            ContentValues name = new ContentValues(); name.put(ContactsContract.Data.RAW_CONTACT_ID, rawId); name.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE); name.put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, a.getString("name")); cr.insert(ContactsContract.Data.CONTENT_URI, name);
            ContentValues ph = new ContentValues(); ph.put(ContactsContract.Data.RAW_CONTACT_ID, rawId); ph.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE); ph.put(ContactsContract.CommonDataKinds.Phone.NUMBER, a.getString("phone")); ph.put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE); cr.insert(ContactsContract.Data.CONTENT_URI, ph);
            return obj("rawContactId", rawId);
        }
        long rawId = a.getLong("rawContactId");
        if (n.equals("contacts.delete")) return obj("deleted", cr.delete(ContactsContract.RawContacts.CONTENT_URI, ContactsContract.RawContacts._ID + "=?", new String[]{String.valueOf(rawId)}) > 0);
        if (n.equals("contacts.update")) {
            if (a.has("name")) {
                ContentValues v = new ContentValues(); v.put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, a.getString("name"));
                cr.update(ContactsContract.Data.CONTENT_URI, v, ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?", new String[]{String.valueOf(rawId), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE});
            }
            if (a.has("phone")) {
                ContentValues v = new ContentValues(); v.put(ContactsContract.CommonDataKinds.Phone.NUMBER, a.getString("phone"));
                cr.update(ContactsContract.Data.CONTENT_URI, v, ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?", new String[]{String.valueOf(rawId), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE});
            }
            return obj("updated", true);
        }
        return new JSONObject();
    }

    private JSONObject sms(String n, JSONObject a) throws Exception {
        ContentResolver cr = getContentResolver();
        if (n.equals("sms.read")) {
            Uri uri = Telephony.Sms.CONTENT_URI;
            String folder = a.optString("folder", "all");
            if ("inbox".equals(folder)) uri = Telephony.Sms.Inbox.CONTENT_URI;
            else if ("sent".equals(folder)) uri = Telephony.Sms.Sent.CONTENT_URI;
            else if ("draft".equals(folder)) uri = Telephony.Sms.Draft.CONTENT_URI;
            int limit = a.optInt("limit", 100);
            Cursor c = cr.query(uri, new String[]{Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ}, null, null, Telephony.Sms.DATE + " DESC");
            JSONArray arr = new JSONArray();
            if (c != null) { while (c.moveToNext() && arr.length() < limit) { JSONObject o = new JSONObject(); o.put("id", c.getLong(0)); o.put("address", c.getString(1)); o.put("body", c.getString(2)); o.put("date", c.getLong(3)); o.put("type", c.getInt(4)); o.put("read", c.getInt(5) != 0); arr.put(o); } c.close(); }
            return obj("messages", arr);
        }
        if (n.equals("sms.send")) {
            SmsManager.getDefault().sendTextMessage(a.getString("to"), null, a.getString("message"), null, null);
            return obj("sent", true);
        }
        if (!isDefaultSmsApp()) throw new SecurityException("operasi ubah SMS provider biasanya mensyaratkan Phone To MCP menjadi aplikasi SMS default");
        String id = a.getString("id");
        if (n.equals("sms.delete")) return obj("deleted", cr.delete(Telephony.Sms.CONTENT_URI, Telephony.Sms._ID + "=?", new String[]{id}) > 0);
        if (n.equals("sms.mark_read")) { ContentValues v = new ContentValues(); v.put(Telephony.Sms.READ, a.getBoolean("read") ? 1 : 0); return obj("updated", cr.update(Telephony.Sms.CONTENT_URI, v, Telephony.Sms._ID + "=?", new String[]{id}) > 0); }
        return new JSONObject();
    }

    private boolean isDefaultSmsApp() {
        if (Build.VERSION.SDK_INT >= 19) return getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(this));
        return false;
    }

    private JSONObject callLog(String n, JSONObject a) throws Exception {
        ContentResolver cr = getContentResolver();
        if (n.equals("calllog.read")) {
            int limit = a.optInt("limit", 100);
            Cursor c = cr.query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME}, null, null, CallLog.Calls.DATE + " DESC");
            JSONArray arr = new JSONArray();
            if (c != null) { while (c.moveToNext() && arr.length() < limit) { JSONObject o = new JSONObject(); o.put("id", c.getLong(0)); o.put("number", c.getString(1)); o.put("date", c.getLong(2)); o.put("durationSec", c.getLong(3)); o.put("type", c.getInt(4)); o.put("name", c.getString(5)); arr.put(o); } c.close(); }
            return obj("calls", arr);
        }
        boolean ok = cr.delete(CallLog.Calls.CONTENT_URI, CallLog.Calls._ID + "=?", new String[]{a.getString("id")}) > 0;
        return obj("deleted", ok);
    }

    private JSONObject phone(String n, JSONObject a) throws Exception {
        String num = a.getString("number");
        Intent i = new Intent(n.equals("phone.call") ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num)));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        return obj("opened", true);
    }

    private JSONObject calendar(String n, JSONObject a) throws Exception {
        ContentResolver cr = getContentResolver();
        if (n.equals("calendar.read")) {
            long from = a.has("from") ? a.getLong("from") : System.currentTimeMillis();
            long to = a.has("to") ? a.getLong("to") : from + 30L * 24 * 60 * 60 * 1000;
            int limit = a.optInt("limit", 100);
            Cursor c = cr.query(CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(String.valueOf(from)).appendPath(String.valueOf(to)).build(), new String[]{CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION}, null, null, CalendarContract.Instances.BEGIN + " ASC");
            JSONArray arr = new JSONArray();
            if (c != null) { while (c.moveToNext() && arr.length() < limit) { JSONObject o = new JSONObject(); o.put("id", c.getLong(0)); o.put("title", c.getString(1)); o.put("start", c.getLong(2)); o.put("end", c.getLong(3)); o.put("location", c.getString(4)); arr.put(o); } c.close(); }
            return obj("events", arr);
        }
        if (n.equals("calendar.create")) {
            Cursor cal = cr.query(CalendarContract.Calendars.CONTENT_URI, new String[]{CalendarContract.Calendars._ID}, CalendarContract.Calendars.VISIBLE + "=1 AND " + CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=200", null, null);
            long calId = -1; if (cal != null) { if (cal.moveToFirst()) calId = cal.getLong(0); cal.close(); }
            if (calId < 0) throw new IllegalStateException("tidak ada kalender yang dapat ditulis");
            ContentValues v = new ContentValues(); v.put(CalendarContract.Events.CALENDAR_ID, calId); v.put(CalendarContract.Events.TITLE, a.getString("title")); v.put(CalendarContract.Events.DESCRIPTION, a.optString("description", "")); v.put(CalendarContract.Events.DTSTART, a.getLong("start")); v.put(CalendarContract.Events.DTEND, a.getLong("end")); v.put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().getID()); v.put(CalendarContract.Events.ALL_DAY, 0); Uri u = cr.insert(CalendarContract.Events.CONTENT_URI, v); return obj("id", u == null ? -1 : android.content.ContentUris.parseId(u));
        }
        String id = a.getString("id");
        if (n.equals("calendar.delete")) return obj("deleted", cr.delete(CalendarContract.Events.CONTENT_URI, CalendarContract.Events._ID + "=?", new String[]{id}) > 0);
        ContentValues v = new ContentValues(); if (a.has("title")) v.put(CalendarContract.Events.TITLE, a.getString("title")); if (a.has("start")) v.put(CalendarContract.Events.DTSTART, a.getLong("start")); if (a.has("end")) v.put(CalendarContract.Events.DTEND, a.getLong("end")); return obj("updated", cr.update(CalendarContract.Events.CONTENT_URI, v, CalendarContract.Events._ID + "=?", new String[]{id}) > 0);
    }

    private JSONObject media(String n, JSONObject a) throws Exception {
        if (n.equals("media.delete")) {
            return obj("deleted", getContentResolver().delete(Uri.parse(a.getString("uri")), null, null) > 0);
        }
        Uri uri; String[] projection;
        if (n.equals("media.images")) uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        else if (n.equals("media.videos")) uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        else uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        int limit = a.optInt("limit", 100);
        projection = new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE};
        Cursor c = getContentResolver().query(uri, projection, null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
        JSONArray arr = new JSONArray();
        if (c != null) { while (c.moveToNext() && arr.length() < limit) { JSONObject o = new JSONObject(); long id = c.getLong(0); o.put("id", id); o.put("name", c.getString(1)); o.put("size", c.getLong(2)); o.put("modified", c.getLong(3)); o.put("mime", c.getString(4)); o.put("uri", Uri.withAppendedPath(uri, String.valueOf(id)).toString()); arr.put(o); } c.close(); }
        return obj("items", arr);
    }

    private JSONObject cameraOpen() throws Exception {
        Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        return obj("opened", true, "note", "pengambilan foto dikendalikan oleh aplikasi kamera sistem");
    }

    private JSONObject audio(String n, JSONObject a) throws Exception {
        if (n.equals("audio.record_start")) {
            ensureMicrophoneForeground();
            if (recorder != null) throw new IllegalStateException("perekaman sudah berjalan");
            recordingFile = FileOps.resolve(this, a.getString("root"), a.getString("path"));
            File parent = recordingFile.getParentFile(); if (parent != null) parent.mkdirs();
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setOutputFile(recordingFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            return obj("recording", true, "path", a.getString("path"));
        }
        if (n.equals("audio.record_stop")) return stopRecordingInternal();
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int stream = streamFrom(a.optString("stream", "music"));
        if (n.equals("audio.volume_get")) return obj("stream", a.optString("stream", "music"), "value", am.getStreamVolume(stream), "max", am.getStreamMaxVolume(stream));
        int max = am.getStreamMaxVolume(stream); int val = Math.max(0, Math.min(max, a.getInt("value"))); am.setStreamVolume(stream, val, 0); return obj("value", val, "max", max);
    }

    private JSONObject stopRecordingInternal() {
        if (recorder == null) return obj("recording", false, "wasRunning", false);
        try { recorder.stop(); } catch (Exception ignored) { }
        try { recorder.reset(); recorder.release(); } catch (Exception ignored) { }
        recorder = null;
        return obj("recording", false, "path", recordingFile == null ? JSONObject.NULL : recordingFile.getAbsolutePath());
    }

    private int streamFrom(String s) {
        if ("ring".equalsIgnoreCase(s)) return AudioManager.STREAM_RING;
        if ("alarm".equalsIgnoreCase(s)) return AudioManager.STREAM_ALARM;
        if ("notification".equalsIgnoreCase(s)) return Build.VERSION.SDK_INT >= 5 ? AudioManager.STREAM_NOTIFICATION : AudioManager.STREAM_RING;
        if ("system".equalsIgnoreCase(s)) return AudioManager.STREAM_SYSTEM;
        if ("voice_call".equalsIgnoreCase(s)) return AudioManager.STREAM_VOICE_CALL;
        return AudioManager.STREAM_MUSIC;
    }

private JSONObject tts(String n, JSONObject a) throws Exception {
    if (n.equals("tts.stop")) {
        stopTts();
        return obj("stopped", true);
    }
    final String text = a.getString("text").trim();
    if (text.length() == 0) throw new IllegalArgumentException("teks TTS kosong");
    final String lang = a.optString("lang", "id").trim().length() == 0 ? "id" : a.optString("lang", "id");
    final boolean add = "add".equalsIgnoreCase(a.optString("queue", "flush"));
    mainHandler.post(new Runnable() {
        public void run() {
            synchronized (googleTtsQueue) {
                if (!add) googleTtsQueue.clear();
                googleTtsLang = lang;
                appendTtsChunks(text, googleTtsQueue);
                if (!googleTtsPlaying) playNextGoogleTts();
            }
        }
    });
    return obj("queued", true, "provider", "Google Translate TTS", "savedFile", false);
}

private void appendTtsChunks(String text, ArrayList<String> queue) {
    int max = 180;
    int pos = 0;
    while (pos < text.length()) {
        int end = Math.min(text.length(), pos + max);
        if (end < text.length()) {
            int cut = text.lastIndexOf(' ', end);
            if (cut > pos + 40) end = cut;
        }
        queue.add(text.substring(pos, end).trim());
        pos = end;
        while (pos < text.length() && text.charAt(pos) == ' ') pos++;
    }
}

private void playNextGoogleTts() {
    synchronized (googleTtsQueue) {
        if (googleTtsQueue.size() == 0) {
            googleTtsPlaying = false;
            releaseGoogleTtsPlayer();
            return;
        }
        googleTtsPlaying = true;
        String chunk = googleTtsQueue.remove(0);
        String url = buildGoogleTtsUrl(chunk, googleTtsLang);
        try {
            releaseGoogleTtsPlayer();
            MediaPlayer mp = new MediaPlayer();
            googleTtsPlayer = mp;
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            java.util.HashMap<String, String> headers = new java.util.HashMap<String, String>();
            headers.put("User-Agent", "Mozilla/5.0 (Android) PhoneToMCP");
            mp.setDataSource(this, Uri.parse(url), headers);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer player) { player.start(); }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer player) {
                    try { player.release(); } catch (Exception ignored) { }
                    googleTtsPlayer = null;
                    playNextGoogleTts();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer player, int what, int extra) {
                    try { player.release(); } catch (Exception ignored) { }
                    googleTtsPlayer = null;
                    playNextGoogleTts();
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            googleTtsPlayer = null;
            playNextGoogleTts();
            Log.e(TAG, "Google TTS gagal", e);
        }
    }
}

private String buildGoogleTtsUrl(String text, String lang) {
    try {
        return "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl="
                + URLEncoder.encode(lang, "UTF-8") + "&q=" + URLEncoder.encode(text, "UTF-8");
    } catch (Exception e) {
        return "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=id&q=" + Uri.encode(text);
    }
}

private void releaseGoogleTtsPlayer() {
    if (googleTtsPlayer != null) {
        try { googleTtsPlayer.stop(); } catch (Exception ignored) { }
        try { googleTtsPlayer.reset(); } catch (Exception ignored) { }
        try { googleTtsPlayer.release(); } catch (Exception ignored) { }
        googleTtsPlayer = null;
    }
}

private void stopTts() {
    mainHandler.post(new Runnable() {
        public void run() {
            synchronized (googleTtsQueue) {
                googleTtsQueue.clear();
                googleTtsPlaying = false;
                releaseGoogleTtsPlayer();
            }
        }
    });
}

    private JSONObject vibrate(JSONObject a) {
        long ms = Math.max(1, Math.min(5000, a.optLong("milliseconds", 250)));
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        else v.vibrate(ms);
        return obj("vibratedMs", ms);
    }

    private JSONObject flashlight(JSONObject a) throws Exception {
        if (Build.VERSION.SDK_INT < 23) throw new UnsupportedOperationException("flashlight API memerlukan Android 6+");
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        String selected = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (Boolean.TRUE.equals(flash) && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) { selected = id; break; }
        }
        if (selected == null) throw new IllegalStateException("flash tidak tersedia");
        cm.setTorchMode(selected, a.getBoolean("enabled"));
        return obj("enabled", a.getBoolean("enabled"));
    }

    private JSONObject location() throws Exception {
        ensureLocationForeground();
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location best = null;
        String[] providers = new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (String p : providers) {
            try { Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; } catch (SecurityException ignored) { }
        }
        JSONObject o = new JSONObject();
        if (best == null) { o.put("available", false); o.put("message", "tidak ada lokasi terakhir yang dapat dibaca"); return o; }
        o.put("available", true); o.put("latitude", best.getLatitude()); o.put("longitude", best.getLongitude()); o.put("accuracyMeters", best.getAccuracy()); o.put("altitudeMeters", best.getAltitude()); o.put("time", best.getTime()); o.put("provider", best.getProvider());
        return o;
    }

    private JSONObject wifi(String n, JSONObject a) throws Exception {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (n.equals("wifi.info")) {
            WifiInfo i = wm.getConnectionInfo();
            JSONObject o = new JSONObject(); o.put("enabled", wm.isWifiEnabled()); o.put("ssid", i == null ? JSONObject.NULL : i.getSSID()); o.put("bssid", i == null ? JSONObject.NULL : i.getBSSID()); o.put("rssi", i == null ? 0 : i.getRssi()); o.put("linkSpeedMbps", i == null ? 0 : i.getLinkSpeed()); return o;
        }
        wm.startScan();
        List<ScanResult> list = wm.getScanResults();
        JSONArray arr = new JSONArray(); int limit = a.optInt("limit", 50);
        Collections.sort(list, new Comparator<ScanResult>() { public int compare(ScanResult x, ScanResult y) { return y.level - x.level; } });
        for (ScanResult r : list) { if (arr.length() >= limit) break; JSONObject o = new JSONObject(); o.put("ssid", r.SSID); o.put("bssid", r.BSSID); o.put("level", r.level); o.put("frequency", r.frequency); arr.put(o); }
        return obj("results", arr);
    }

    private JSONObject bluetooth(String n, JSONObject a) throws Exception {
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        if (ba == null) return obj("available", false);
        JSONObject o = new JSONObject(); o.put("available", true); o.put("enabled", ba.isEnabled());
        if (n.equals("bluetooth.paired")) {
            if (Build.VERSION.SDK_INT >= 31 && !PermissionUtil.granted(this, "android.permission.BLUETOOTH_CONNECT")) throw new SecurityException("BLUETOOTH_CONNECT belum diberikan");
            JSONArray arr = new JSONArray(); for (BluetoothDevice d : ba.getBondedDevices()) { JSONObject x = new JSONObject(); x.put("name", d.getName()); x.put("address", d.getAddress()); x.put("bondState", d.getBondState()); arr.put(x); }
            o.put("paired", arr);
        }
        return o;
    }

    private JSONObject apps(String n, JSONObject a) throws Exception {
        PackageManager pm = getPackageManager();
        if (n.equals("app.list") || n.equals("pm.read")) {
            List<ApplicationInfo> infos = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            JSONArray arr = new JSONArray();
            for (ApplicationInfo ai : infos) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
                if (n.equals("app.list")) {
                    Intent launch = pm.getLaunchIntentForPackage(ai.packageName);
                    if (launch == null) continue;
                }
                JSONObject o = new JSONObject(); o.put("package", ai.packageName); o.put("name", pm.getApplicationLabel(ai).toString()); o.put("sourceDir", ai.sourceDir); o.put("systemApp", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0); arr.put(o);
            }
            return obj("apps", arr, "count", arr.length(), "launcherOnly", n.equals("app.list"));
        }
        String pkg = a.getString("package");
        PackageInfo pi = pm.getPackageInfo(pkg, 0);
        if (n.equals("app.info")) return obj("package", pkg, "versionName", pi.versionName, "versionCode", pi.versionCode, "targetSdk", pi.applicationInfo.targetSdkVersion, "label", pm.getApplicationLabel(pi.applicationInfo), "sourceDir", pi.applicationInfo.sourceDir, "dataDir", pi.applicationInfo.dataDir, "uid", pi.applicationInfo.uid);
        if (n.equals("app.open")) { Intent i = pm.getLaunchIntentForPackage(pkg); if (i == null) throw new IllegalArgumentException("launcher activity tidak ditemukan"); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return obj("opened", true, "package", pkg); }
        if (n.equals("app.uninstall_request")) { Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return obj("requested", true); }
        return new JSONObject();
    }

    private JSONObject shareText(JSONObject a) throws Exception {
        Intent i = new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_TEXT, a.getString("text")); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); Intent chooser = Intent.createChooser(i, a.optString("title", "Bagikan")); chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(chooser); return obj("opened", true);
    }

    private JSONObject shareFile(JSONObject a) throws Exception {
        File f = FileOps.resolve(this, a.getString("root"), a.getString("path"));
        if (!f.isFile()) throw new IllegalArgumentException("bukan file");
        Uri u = new Uri.Builder().scheme("content").authority("mcp.android.phone.files").appendPath("file").appendQueryParameter("root", a.getString("root")).appendQueryParameter("path", a.getString("path")).appendQueryParameter("mime", a.optString("mime", "application/octet-stream")).build();
        Intent i = new Intent(Intent.ACTION_SEND); i.setType(a.optString("mime", "application/octet-stream")); i.putExtra(Intent.EXTRA_STREAM, u); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(Intent.createChooser(i, "Bagikan file")); return obj("opened", true, "uri", u.toString());
    }

    private JSONObject download(JSONObject a) throws Exception {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        Uri u = Uri.parse(a.getString("url"));
        DownloadManager.Request r = new DownloadManager.Request(u);
        if (a.has("filename")) r.setTitle(a.getString("filename"));
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        if (Build.VERSION.SDK_INT >= 29) r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, a.optString("filename", "download"));
        else r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, a.optString("filename", "download"));
        long id = dm.enqueue(r);
        return obj("downloadId", id);
    }

    private JSONObject notifications(String n, JSONObject a) throws Exception {
        if (n.equals("notification.send")) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            int id = a.getInt("id");
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
            b.setSmallIcon(getApplicationInfo().icon).setContentTitle(a.getString("title")).setContentText(a.getString("message")).setAutoCancel(true).setOnlyAlertOnce(true);
            nm.notify(id, b.build());
            return obj("notified", true, "id", id);
        }
        if (PhoneNotificationListener.INSTANCE == null) return obj("connected", false, "notifications", new JSONArray(), "recent", new JSONArray());
        if (n.equals("notifications.read")) return obj("connected", true, "notifications", PhoneNotificationListener.INSTANCE.activeJson(), "recent", PhoneNotificationListener.INSTANCE.recentJson());
        PhoneNotificationListener.INSTANCE.cancelNotification(a.getString("key"));
        return obj("dismissed", true);
    }

    private JSONObject usage(JSONObject a) throws Exception {
        android.app.usage.UsageStatsManager um = (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        long end = System.currentTimeMillis(); long start = end - Math.max(1, a.optInt("days", 1)) * 24L * 60 * 60 * 1000;
        List<android.app.usage.UsageStats> stats = um.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end);
        JSONArray arr = new JSONArray(); int limit = a.optInt("limit", 100);
        if (stats != null) for (android.app.usage.UsageStats s : stats) { if (arr.length() >= limit) break; JSONObject o = new JSONObject(); o.put("package", s.getPackageName()); o.put("lastTimeUsed", s.getLastTimeUsed()); o.put("totalTimeForegroundMs", s.getTotalTimeInForeground()); arr.put(o); }
        return obj("stats", arr, "accessEnabled", usageAccessEnabled());
    }

    private boolean usageAccessEnabled() {
        if (Build.VERSION.SDK_INT < 21) return false;
        android.app.AppOpsManager appOps = (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), getPackageName());
        return mode == android.app.AppOpsManager.MODE_ALLOWED;
    }

    private JSONObject accessibility(String n, JSONObject a) throws Exception {
        if (n.equals("accessibility.status")) return obj("enabled", PhoneAccessibilityService.INSTANCE != null);
        if (PhoneAccessibilityService.INSTANCE == null) throw new SecurityException("aksesibilitas Phone To MCP belum aktif");
        String action = a.getString("action"); int id;
        if ("home".equals(action)) id = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME;
        else if ("back".equals(action)) id = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK;
        else if ("recents".equals(action)) id = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS;
        else if ("notifications".equals(action)) id = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
        else if ("quick_settings".equals(action)) id = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
        else throw new IllegalArgumentException("aksi aksesibilitas tidak dikenal");
        return obj("performed", PhoneAccessibilityService.INSTANCE.performGlobalAction(id));
    }


    private JSONObject sensor(String n, JSONObject a) throws Exception {
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (n.equals("sensor.list")) {
            JSONArray arr = new JSONArray();
            List<Sensor> list = sm.getSensorList(Sensor.TYPE_ALL);
            for (Sensor s : list) {
                JSONObject o = new JSONObject();
                o.put("type", s.getType());
                o.put("name", s.getName());
                o.put("vendor", s.getVendor());
                o.put("version", s.getVersion());
                o.put("powerMa", s.getPower());
                o.put("maxRange", s.getMaximumRange());
                o.put("resolution", s.getResolution());
                o.put("delay", s.getMinDelay());
                arr.put(o);
            }
            return obj("sensors", arr, "count", arr.length());
        }
        final Sensor sensor = sm.getDefaultSensor(a.getInt("sensorType"));
        if (sensor == null) throw new IllegalArgumentException("sensor tidak tersedia");
        final JSONObject out = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(1);
        SensorEventListener listener = new SensorEventListener() {
            public void onSensorChanged(SensorEvent event) {
                try {
                    JSONArray values = new JSONArray();
                    for (float v : event.values) values.put(v);
                    out.put("sensorType", event.sensor.getType());
                    out.put("name", event.sensor.getName());
                    out.put("timestampNanos", event.timestamp);
                    out.put("accuracy", event.accuracy);
                    out.put("values", values);
                } catch (Exception ignored) {
                }
                latch.countDown();
            }
            public void onAccuracyChanged(Sensor s, int accuracy) {
            }
        };
        Handler h = new Handler(Looper.getMainLooper());
        if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, h)) throw new IllegalStateException("gagal mendaftarkan sensor");
        try {
            latch.await(Math.max(100, Math.min(3000, a.optInt("timeoutMs", 1200))), TimeUnit.MILLISECONDS);
        } finally {
            sm.unregisterListener(listener);
        }
        if (out.length() == 0) throw new IllegalStateException("sensor tidak mengirim sampel dalam batas waktu");
        return out;
    }

    private JSONObject brightnessGet() throws Exception {
        return obj("value", Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS));
    }

    private JSONObject brightnessSet(JSONObject a) throws Exception {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(this)) throw new SecurityException("WRITE_SETTINGS belum diaktifkan");
        int value = Math.max(0, Math.min(255, a.getInt("value")));
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
        return obj("value", value);
    }

    private JSONObject openSetting(String n) {
        Intent i;
        if (n.equals("settings.app_info")) i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
        else if (n.equals("settings.wifi")) i = new Intent(Settings.ACTION_WIFI_SETTINGS);
        else if (n.equals("settings.bluetooth")) i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        else if (n.equals("settings.notifications")) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return obj("opened", true);
    }

    private JSONObject specialStatus() throws Exception {
        JSONObject o = new JSONObject();
        o.put("allFiles", PermissionUtil.hasAllFiles(this));
        o.put("batteryOptimizationIgnored", ((PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()));
        o.put("notificationListener", PhoneNotificationListener.INSTANCE != null);
        o.put("accessibility", PhoneAccessibilityService.INSTANCE != null);
        o.put("usageAccess", usageAccessEnabled());
        o.put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this));
        o.put("writeSettings", Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this));
        return o;
    }

    private JSONObject toast(final JSONObject a) throws Exception {
        final String message = a.getString("message");
        final int duration = "long".equalsIgnoreCase(a.optString("duration", "short")) ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT;
        mainHandler.post(new Runnable() { public void run() { Toast.makeText(getApplicationContext(), message, duration).show(); } });
        return obj("shown", true);
    }

    private JSONObject openUrl(JSONObject a) throws Exception {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url"))); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return obj("opened", true);
    }

    private JSONObject clipboardRead() throws Exception {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (!cm.hasPrimaryClip()) return obj("hasText", false);
        ClipData d = cm.getPrimaryClip(); String t = d != null && d.getItemCount() > 0 ? String.valueOf(d.getItemAt(0).coerceToText(this)) : ""; return obj("hasText", true, "text", t);
    }

    private JSONObject clipboardWrite(JSONObject a) throws Exception {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Phone To MCP", a.getString("text")));
        return obj("written", true);
    }

    private JSONObject obj(Object... values) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i + 1 < values.length; i += 2) o.put(String.valueOf(values[i]), values[i + 1]); } catch (Exception ignored) { }
        return o;
    }

    private String join(String[] a) {
        if (a == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) b.append(","); b.append(a[i]); }
        return b.toString();
    }

    private String safe(Exception e) {
        StringWriter sw = new StringWriter(); e.printStackTrace(new PrintWriter(sw)); String s = e.getMessage(); return (s == null ? e.getClass().getSimpleName() : s) + "\n" + sw.toString();
    }
}
