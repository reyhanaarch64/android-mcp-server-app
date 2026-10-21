package mcp.android.phone;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class TerminalExecManager {
    public static final String TERMUX_PACKAGE = "com.termux";
    private static final String TAG = "TerminalExec";
    private static final String PREFIX = "/data/data/com.termux/files/usr/bin/";
    // Android 12/API 31 flag value; declared locally so older AIDE SDKs can compile.
    private static final int FLAG_MUTABLE_COMPAT = 0x02000000;
    private static final ConcurrentHashMap<Long, PendingResult> PENDING = new ConcurrentHashMap<Long, PendingResult>();
    private TerminalExecManager() { }

    private static final class PendingResult {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String stdout = "", stderr = "", error = "";
        volatile int exitCode = -1;
        volatile boolean completed;
    }

    public static boolean isInstalled(Context c) {
        try { c.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0); return true; }
        catch (Exception e) { return false; }
    }
    public static boolean hasRunCommandPermission(Context c) {
        if (!isInstalled(c)) return false;
        try { return c.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED; }
        catch (Exception e) { return false; }
    }

    public static JSONObject run(Context context, String command, String cwd, int timeoutMs) throws Exception {
        if (command == null || command.trim().length() == 0) throw new IllegalArgumentException("command wajib diisi dan tidak boleh kosong.");
        if (command.length() > 100000) throw new IllegalArgumentException("command terlalu panjang (maksimum 100000 karakter).");
        if (timeoutMs < 1000) timeoutMs = 1000;
        if (timeoutMs > 300000) timeoutMs = 300000;
        String workingDir = cwd == null || cwd.trim().length() == 0 ? "/data/data/com.termux/files/home" : cwd.trim();
        if (workingDir.length() > 2048) throw new IllegalArgumentException("cwd terlalu panjang.");
        String cwdBefore = context.getSharedPreferences("terminal", Context.MODE_PRIVATE)
                .getString("last_cwd", "/data/data/com.termux/files/home");
        TerminalLogDb db = new TerminalLogDb(context);
        long started = System.currentTimeMillis();
        String termuxState = isInstalled(context) ? TERMUX_PACKAGE : "com.termux (not installed)";
        long id = db.insertPending(command, cwdBefore, workingDir, termuxState);
        if (id <= 0) { db.close(); throw new IllegalStateException("Gagal mencatat command ke terminal.db."); }
        if (!isInstalled(context)) {
            db.markRejected(id, System.currentTimeMillis() - started, "termux_not_installed", "Termux tidak terpasang (com.termux).");
            db.close(); throw new IllegalStateException("Termux tidak terpasang (com.termux).");
        }
        if (!hasRunCommandPermission(context)) {
            String message = "Izin RUN_COMMAND belum diberikan. Buka Info Aplikasi Phone To MCP > Izin tambahan > Run commands in Termux environment.";
            db.markRejected(id, System.currentTimeMillis() - started, "permission_denied", message);
            db.close(); throw new SecurityException(message);
        }
        PendingResult pending = new PendingResult(); PENDING.put(Long.valueOf(id), pending);
        try {
            Intent intent = new Intent();
            intent.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService");
            intent.setAction("com.termux.RUN_COMMAND");
            intent.putExtra("com.termux.RUN_COMMAND_PATH", PREFIX + "bash");
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", command});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir);
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            intent.putExtra("com.termux.RUN_COMMAND_LABEL", "Phone To MCP terminal.exec");
            intent.putExtra("com.termux.RUN_COMMAND_DESCRIPTION", "Command requested by the connected MCP client.");
            Intent resultIntent = new Intent(context, TerminalResultService.class);
            resultIntent.putExtra(TerminalResultService.EXTRA_EXECUTION_ID, id);
            int requestCode = (int) (id & 0x7fffffff);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= 31) flags |= FLAG_MUTABLE_COMPAT;
            PendingIntent resultPending = PendingIntent.getService(context, requestCode, resultIntent, flags);
            intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultPending);
            intent.putExtra("pendingIntent", resultPending);
            intent.putExtra("com.termux.execute.pendingIntent", resultPending);
            try {
                context.startService(intent);
                context.getSharedPreferences("terminal", Context.MODE_PRIVATE).edit().putString("last_cwd", workingDir).apply();
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - started;
                db.markDispatchError(id, duration, e.getClass().getSimpleName() + ": " + e.getMessage());
                throw new IllegalStateException("Gagal mengirim command ke Termux: " + e.getMessage(), e);
            }
            boolean received = pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            long duration = System.currentTimeMillis() - started;
            JSONObject out = new JSONObject();
            out.put("command", command); out.put("cwdBefore", cwdBefore); out.put("cwd", workingDir);
            out.put("stdout", pending.stdout); out.put("stderr", pending.stderr);
            out.put("exitCode", pending.exitCode); out.put("durationMs", duration);
            out.put("completed", received && pending.completed); out.put("timedOut", !received);
            out.put("termux", TERMUX_PACKAGE); out.put("executionId", id);
            if (!received) {
                String message = "Timeout menunggu hasil dari Termux setelah " + timeoutMs + " ms. Perintah mungkin masih berjalan di Termux; timeout ini tidak menjamin proses dihentikan.";
                out.put("error", message);
                db.markTimeout(id, duration, message);
            } else if (pending.error.length() > 0) {
                out.put("error", pending.error);
                if (!pending.completed) out.put("completed", false);
            }
            return out;
        } finally {
            PENDING.remove(Long.valueOf(id));
            db.close();
        }
    }

    public static void receive(Context context, long id, String stdout, String stderr, int exitCode, boolean completed, String error) {
        PendingResult p = PENDING.get(Long.valueOf(id));
        TerminalLogDb db = null;
        try {
            db = new TerminalLogDb(context);
            db.updateResult(id, stdout, stderr, exitCode, -1L, completed, false,
                    completed ? "completed" : "termux_error", error);
        } catch (Exception e) { Log.e(TAG, "Gagal menyimpan hasil Termux", e); }
        finally { if (db != null) db.close(); }
        if (p != null) {
            p.stdout = stdout == null ? "" : stdout; p.stderr = stderr == null ? "" : stderr;
            p.exitCode = exitCode; p.completed = completed; p.error = error == null ? "" : error;
            p.latch.countDown();
        }
    }
}
