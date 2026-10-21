package mcp.android.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Debug;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class CrashDiagnostics {
    private static final String TAG = "CrashDiagnostics";
    private static final String PREF = "crash_diagnostics";
    private static final String KEY_STATUS = "service_status"; // 0 = started/not normally stopped, 1 = normal stop
    private static final String KEY_SESSION = "session_id";
    private static final String KEY_STARTED = "started_at";
    private static final String KEY_REPORTED = "last_reported_session";
    private static final Object LOCK = new Object();
    private CrashDiagnostics() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    public static void event(Context c, String message) {
        synchronized (LOCK) {
            File f = new File(c.getFilesDir(), "diagnostics.log");
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(f, true);
                String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                        + " [" + android.os.Process.myPid() + "] " + safe(message) + "\n";
                out.write(line.getBytes("UTF-8")); out.flush();
            } catch (Exception e) { Log.e(TAG, "Tidak dapat menyimpan diagnostics.log", e); }
            finally { if (out != null) try { out.close(); } catch (Exception ignored) { } }
            trimInternalLog(f, 500);
        }
    }

    private static void trimInternalLog(File f, int maxLines) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            ArrayList<String> lines = new ArrayList<String>(); String line;
            while ((line = r.readLine()) != null) { lines.add(line); if (lines.size() > maxLines) lines.remove(0); }
            r.close(); r = null;
            FileOutputStream out = new FileOutputStream(f, false);
            for (String s : lines) out.write((s + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) { }
        finally { if (r != null) try { r.close(); } catch (Exception ignored) { } }
    }

    public static void checkPreviousSession(Context c) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningServiceInfo> services = am.getRunningServices(100);
            for (android.app.ActivityManager.RunningServiceInfo info : services) {
                if (PhoneMcpService.class.getName().equals(info.service.getClassName()) && info.pid != 0) return;
            }
        } catch (Exception ignored) { }
        synchronized (LOCK) { checkPreviousSessionLocked(c); }
    }

    private static void checkPreviousSessionLocked(Context c) {
        SharedPreferences p = prefs(c);
        int status = p.getInt(KEY_STATUS, 1);
        String session = p.getString(KEY_SESSION, "");
        if (status == 0 && session.length() > 0 && !session.equals(p.getString(KEY_REPORTED, ""))) {
            File report = createReport(c, session, p.getLong(KEY_STARTED, 0L));
            if (report != null) {
                p.edit().putString(KEY_REPORTED, session).putString("last_report_path", report.getAbsolutePath()).apply();
                event(c, "Crash report dibuat: " + report.getAbsolutePath());
            } else {
                event(c, "Gagal membuat crash report untuk session " + session);
            }
        }
    }

    public static void markServiceStarting(Context c) {
        synchronized (LOCK) {
            checkPreviousSessionLocked(c);
            String session = UUID.randomUUID().toString();
            prefs(c).edit().putInt(KEY_STATUS, 0).putString(KEY_SESSION, session)
                    .putLong(KEY_STARTED, System.currentTimeMillis()).apply();
            event(c, "SERVICE_START session=" + session + " process=" + android.os.Process.myPid());
        }
    }

    public static void markNormalStop(Context c, String reason) {
        synchronized (LOCK) {
            event(c, "SERVICE_NORMAL_STOP reason=" + safe(reason));
            prefs(c).edit().putInt(KEY_STATUS, 1).putLong("normal_stop_at", System.currentTimeMillis()).apply();
        }
    }

    public static void markServiceDestroy(Context c) {
        event(c, "onDestroy dipanggil; status tidak diubah karena onDestroy tidak menjamin penghentian normal.");
    }

    private static File createReport(Context c, String session, long startedAt) {
        String stamp = new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.US).format(new Date());
        StringBuilder b = new StringBuilder();
        b.append("Phone To MCP - Laporan diagnosis service\n");
        b.append("Waktu laporan: ").append(new Date()).append("\n");
        b.append("Session terakhir: ").append(session).append("\n");
        b.append("Waktu mulai: ").append(startedAt > 0 ? new Date(startedAt) : "tidak tersedia").append("\n");
        b.append("Status terakhir: 0 (tidak tercatat berhenti normal)\n\n");
        b.append("KESIMPULAN\n");
        b.append("Service terindikasi berhenti tanpa menulis penanda penghentian normal. Penanda ini tidak bisa membedakan sendiri antara crash, force-stop, penghentian oleh sistem karena tekanan memori, atau penghentian proses lainnya.\n\n");
        b.append("INFORMASI PROSES SAAT LAPORAN DIBUAT\n");
        b.append("PID sekarang: ").append(android.os.Process.myPid()).append("\n");
        b.append("Versi Android: ").append(android.os.Build.VERSION.RELEASE).append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi);
            b.append("RAM tersedia sekarang: ").append(mi.availMem / (1024L * 1024L)).append(" MB\n");
            b.append("RAM rendah sekarang: ").append(mi.lowMemory).append("\n");
        } catch (Exception e) { b.append("Info RAM: tidak tersedia (" + e.getMessage() + ")\n"); }
        b.append("Catatan: kondisi RAM saat laporan bukan bukti kondisi RAM pada saat service berhenti.\n\n");
        b.append("LOG INTERNAL APLIKASI (maksimum 500 baris)\n");
        b.append(readTail(new File(c.getFilesDir(), "diagnostics.log"), 500));
        b.append("\nLOGCAT YANG TERKAIT APLIKASI INI SAJA\n");
        String logcat = readOwnLogcat(c);
        if (logcat.length() == 0) b.append("Tidak ada logcat terkait aplikasi yang dapat dibaca. Ini tidak membuktikan bahwa tidak ada crash.\n");
        else b.append(logcat);
        b.append("\nANALISIS BERDASARKAN BUKTI\n");
        if (logcat.contains("FATAL EXCEPTION") || logcat.contains("Fatal signal") || logcat.contains("Process: mcp.android.phone")) {
            b.append("Ditemukan indikasi crash native/Java yang berkaitan dengan proses aplikasi. Periksa blok FATAL EXCEPTION/Fatal signal di logcat di atas.\n");
        } else {
            b.append("Tidak ditemukan bukti crash yang meyakinkan pada logcat aplikasi yang tersedia. Penyebab pasti belum dapat ditentukan. Kemungkinan umum meliputi force-stop, penghentian oleh sistem, atau logcat sudah tidak menyimpan bukti yang relevan; tidak satu pun dapat dipastikan dari penanda status saja.\n");
        }
        b.append("\nBatasan: logcat Android dapat membatasi akses dan menghapus log lama. Laporan ini tidak membaca log aplikasi lain dan tidak menyatakan penyebab pasti tanpa bukti.\n");
        byte[] bytes;
        try { bytes = b.toString().getBytes("UTF-8"); } catch (Exception e) { return null; }
        File target = null;
        try {
            File root = Environment.getExternalStorageDirectory();
            target = new File(root, "mcp_log_" + stamp + ".txt");
            write(target, bytes); return target;
        } catch (Exception e) { Log.w(TAG, "Tidak dapat menulis ke /sdcard, mencoba fallback", e); }
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir != null) { target = new File(dir, "mcp_log_" + stamp + ".txt"); write(target, bytes); return target; }
        } catch (Exception e) { Log.w(TAG, "Fallback external gagal", e); }
        try { target = new File(c.getFilesDir(), "mcp_log_" + stamp + ".txt"); write(target, bytes); return target; }
        catch (Exception e) { Log.e(TAG, "Gagal menulis crash report", e); return null; }
    }

    private static void write(File f, byte[] bytes) throws Exception {
        File parent = f.getParentFile(); if (parent != null && !parent.exists() && !parent.mkdirs()) throw new Exception("Tidak bisa membuat direktori laporan");
        FileOutputStream out = new FileOutputStream(f); try { out.write(bytes); out.flush(); } finally { out.close(); }
    }

    private static String readTail(File f, int max) {
        if (!f.exists()) return "(belum ada log internal)\n";
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f)); ArrayList<String> lines = new ArrayList<String>(); String line;
            while ((line = r.readLine()) != null) { lines.add(line); if (lines.size() > max) lines.remove(0); }
            StringBuilder b = new StringBuilder(); for (String s : lines) b.append(s).append('\n'); return b.toString();
        } catch (Exception e) { return "Gagal membaca log internal: " + e.getMessage() + "\n"; }
        finally { if (r != null) try { r.close(); } catch (Exception ignored) { } }
    }

    private static String readOwnLogcat(Context context) {
        Process process = null; BufferedReader r = null;
        try {
            java.util.HashSet<String> ownPids = new java.util.HashSet<String>();
            ownPids.add(String.valueOf(android.os.Process.myPid()));
            File internal = new File(context.getFilesDir(), "diagnostics.log");
            // The persisted event lines include the PID of every process that ran this service.
            if (internal.exists()) {
                BufferedReader pidReader = new BufferedReader(new FileReader(internal));
                String pidLine;
                while ((pidLine = pidReader.readLine()) != null) {
                    int left = pidLine.indexOf('['); int right = pidLine.indexOf(']', left + 1);
                    if (left >= 0 && right > left) {
                        String candidate = pidLine.substring(left + 1, right).trim();
                        try { Integer.parseInt(candidate); ownPids.add(candidate); } catch (Exception ignored) { }
                    }
                }
                pidReader.close();
            }
            process = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "2500").redirectErrorStream(true).start();
            r = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            StringBuilder out = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) continue;
                String pid = parts[2];
                if (ownPids.contains(pid)) out.append(line).append('\n');
            }
            try { process.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String result = out.toString();
            if (result.length() > 60000) result = result.substring(result.length() - 60000);
            return result;
        } catch (Exception e) { return "Logcat tidak dapat dibaca: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n"; }
        finally { if (r != null) try { r.close(); } catch (Exception ignored) { } if (process != null) try { process.destroy(); } catch (Exception ignored) { } }
    }
    private static String safe(String s) { return s == null ? "" : s; }
}
