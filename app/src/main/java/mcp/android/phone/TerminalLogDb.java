package mcp.android.phone;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public final class TerminalLogDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "terminal.db";
    private static final int DB_VERSION = 1;
    public TerminalLogDb(Context c) { super(c, DB_NAME, null, DB_VERSION); }

    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE executions (id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, finished_at INTEGER DEFAULT 0, command TEXT NOT NULL, cwd_before TEXT, cwd TEXT, stdout TEXT, stderr TEXT, exit_code INTEGER DEFAULT -1, duration_ms INTEGER DEFAULT 0, completed INTEGER DEFAULT 0, timed_out INTEGER DEFAULT 0, termux TEXT, status TEXT, error TEXT)");
        db.execSQL("CREATE INDEX idx_executions_started ON executions(started_at DESC)");
    }
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public synchronized long insertPending(String command, String cwdBefore, String cwd, String termux) {
        ContentValues v = new ContentValues();
        v.put("started_at", System.currentTimeMillis()); v.put("command", command);
        v.put("cwd_before", cwdBefore); v.put("cwd", cwd); v.put("termux", termux);
        v.put("status", "running"); v.put("completed", 0); v.put("exit_code", -1);
        return getWritableDatabase().insert("executions", null, v);
    }

    public synchronized void markTimeout(long id, long durationMs, String message) {
        ContentValues v = new ContentValues(); v.put("timed_out", 1); v.put("duration_ms", durationMs);
        v.put("status", "timeout_waiting_for_result"); v.put("error", message);
        getWritableDatabase().update("executions", v, "id=? AND completed=0", new String[]{String.valueOf(id)});
    }

    public synchronized void markDispatchError(long id, long durationMs, String message) {
        markRejected(id, durationMs, "dispatch_error", message);
    }

    public synchronized void markRejected(long id, long durationMs, String status, String message) {
        ContentValues v = new ContentValues(); v.put("finished_at", System.currentTimeMillis());
        v.put("duration_ms", durationMs); v.put("completed", 0); v.put("status", status); v.put("error", message);
        getWritableDatabase().update("executions", v, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void updateResult(long id, String stdout, String stderr, int exitCode,
            long durationMs, boolean completed, boolean timedOut, String status, String error) {
        Cursor c = null; boolean wasTimedOut = timedOut; long startedAt = System.currentTimeMillis();
        try { c = getReadableDatabase().rawQuery("SELECT timed_out,started_at FROM executions WHERE id=?", new String[]{String.valueOf(id)}); if (c.moveToFirst()) { if (c.getInt(0) != 0) wasTimedOut = true; startedAt = c.getLong(1); } }
        finally { if (c != null) c.close(); }
        long finishedAt = System.currentTimeMillis();
        if (durationMs < 0L) durationMs = Math.max(0L, finishedAt - startedAt);
        ContentValues v = new ContentValues(); v.put("finished_at", finishedAt);
        v.put("stdout", safe(stdout)); v.put("stderr", safe(stderr)); v.put("exit_code", exitCode);
        v.put("duration_ms", durationMs); v.put("completed", completed ? 1 : 0);
        v.put("timed_out", wasTimedOut ? 1 : 0); v.put("status", wasTimedOut && completed ? "completed_after_timeout" : status);
        v.put("error", error);
        getWritableDatabase().update("executions", v, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized ArrayList<String> readFormatted(int limit) {
        ArrayList<String> rows = new ArrayList<String>(); Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT id,started_at,command,cwd_before,cwd,stdout,stderr,exit_code,duration_ms,completed,timed_out,termux,status,error FROM executions ORDER BY started_at DESC LIMIT " + Math.max(1, Math.min(500, limit)), null);
            SimpleDateFormat f = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault());
            while (c.moveToNext()) {
                StringBuilder b = new StringBuilder();
                b.append("#").append(c.getLong(0)).append("  ").append(f.format(new Date(c.getLong(1)))).append("\n");
                b.append("$ ").append(c.getString(2)).append("\n");
                b.append("cwdBefore: ").append(nvl(c.getString(3))).append("\ncwd: ").append(nvl(c.getString(4))).append("\n");
                b.append("status: ").append(nvl(c.getString(12))).append(" | completed: ").append(c.getInt(9) == 1).append(" | timedOut: ").append(c.getInt(10) == 1).append("\n");
                b.append("exitCode: ").append(c.getInt(7)).append(" | durationMs: ").append(c.getLong(8)).append(" | termux: ").append(nvl(c.getString(11))).append("\n");
                b.append("stdout:\n").append(empty(c.getString(5))).append("\n");
                b.append("stderr:\n").append(empty(c.getString(6))).append("\n");
                if (c.getString(13) != null && c.getString(13).length() > 0) b.append("error:\n").append(c.getString(13)).append("\n");
                rows.add(b.toString());
            }
        } finally { if (c != null) c.close(); }
        return rows;
    }
    public synchronized int clearLogs() { return getWritableDatabase().delete("executions", null, null); }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String nvl(String s) { return s == null ? "(none)" : s; }
    private static String empty(String s) { return s == null || s.length() == 0 ? "(kosong)" : s; }
}
