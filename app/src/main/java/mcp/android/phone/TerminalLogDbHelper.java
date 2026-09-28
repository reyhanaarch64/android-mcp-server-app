package mcp.android.phone;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

public class TerminalLogDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "terminal_logs.db";
    private static final int DB_VERSION = 1;

    public TerminalLogDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE terminal_log (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "timestamp INTEGER NOT NULL," +
                "command TEXT NOT NULL," +
                "cwd_before TEXT," +
                "cwd_after TEXT," +
                "stdout TEXT," +
                "stderr TEXT," +
                "exit_code INTEGER," +
                "duration_ms INTEGER," +
                "error TEXT" +
                ")");
        db.execSQL("CREATE INDEX terminal_log_timestamp_idx ON terminal_log(timestamp DESC)");
    }

    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1) onCreate(db);
    }

    public long insertLog(long timestamp, String command, String cwdBefore, String cwdAfter,
                          String stdout, String stderr, int exitCode, long durationMs, String error) {
        ContentValues v = new ContentValues();
        v.put("timestamp", timestamp);
        v.put("command", limit(command, 120000));
        v.put("cwd_before", limit(cwdBefore, 4096));
        v.put("cwd_after", limit(cwdAfter, 4096));
        v.put("stdout", limit(stdout, 100000));
        v.put("stderr", limit(stderr, 100000));
        v.put("exit_code", exitCode);
        v.put("duration_ms", durationMs);
        v.put("error", limit(error, 25000));
        SQLiteDatabase db = getWritableDatabase();
        return db.insert("terminal_log", null, v);
    }

    public Cursor getAllLogs() {
        return getReadableDatabase().query(
                "terminal_log",
                null,
                null,
                null,
                null,
                null,
                "timestamp DESC, _id DESC");
    }

    public int countLogs() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM terminal_log", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public void clearLogs() {
        getWritableDatabase().delete("terminal_log", null, null);
    }

    public JSONArray toJsonArray(int limit) {
        JSONArray result = new JSONArray();
        Cursor c = getReadableDatabase().query(
                "terminal_log",
                null,
                null,
                null,
                null,
                null,
                "timestamp DESC, _id DESC",
                String.valueOf(Math.max(1, limit)));
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(c.getColumnIndexOrThrow("_id")));
                o.put("timestamp", c.getLong(c.getColumnIndexOrThrow("timestamp")));
                o.put("command", c.getString(c.getColumnIndexOrThrow("command")));
                o.put("cwdBefore", c.getString(c.getColumnIndexOrThrow("cwd_before")));
                o.put("cwdAfter", c.getString(c.getColumnIndexOrThrow("cwd_after")));
                o.put("stdout", c.getString(c.getColumnIndexOrThrow("stdout")));
                o.put("stderr", c.getString(c.getColumnIndexOrThrow("stderr")));
                o.put("exitCode", c.getInt(c.getColumnIndexOrThrow("exit_code")));
                o.put("durationMs", c.getLong(c.getColumnIndexOrThrow("duration_ms")));
                o.put("error", c.getString(c.getColumnIndexOrThrow("error")));
                result.put(o);
            }
        } catch (Exception ignored) {
        } finally {
            c.close();
        }
        return result;
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, max) + "\n[terpotong]";
    }
}
