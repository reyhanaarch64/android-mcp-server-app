package mcp.android.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.database.Cursor;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TerminalLogActivity extends Activity {
    private LinearLayout container;
    private TextView countText;
    private TerminalLogDbHelper db;

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.terminal_log);
        container = (LinearLayout) findViewById(R.id.logContainer);
        countText = (TextView) findViewById(R.id.logCountText);
        db = new TerminalLogDbHelper(this);

        findViewById(R.id.clearLogsButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmClear(); }
        });
        loadLogs();
    }

    protected void onDestroy() {
        if (db != null) db.close();
        super.onDestroy();
    }

    private void loadLogs() {
        container.removeAllViews();
        int count = db.countLogs();
        countText.setText("Jumlah log: " + count);
        Cursor c = db.getAllLogs();
        try {
            while (c.moveToNext()) addLog(c);
        } finally {
            c.close();
        }
        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText("Belum ada histori terminal.");
            empty.setTextSize(15);
            empty.setPadding(0, 24, 0, 24);
            container.addView(empty);
        }
    }

    private void addLog(final Cursor c) {
        final String command = get(c, "command");
        final String stdout = get(c, "stdout");
        final String stderr = get(c, "stderr");
        final String cwdBefore = get(c, "cwd_before");
        final String cwdAfter = get(c, "cwd_after");
        final String error = get(c, "error");
        final int exitCode = c.getInt(c.getColumnIndexOrThrow("exit_code"));
        final long timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"));
        final long duration = c.getLong(c.getColumnIndexOrThrow("duration_ms"));

        TextView item = new TextView(this);
        item.setTypeface(Typeface.MONOSPACE);
        item.setTextSize(13);
        item.setPadding(0, 12, 0, 12);
        item.setText(formatSummary(timestamp, command, cwdAfter, exitCode, duration));
        item.setTextIsSelectable(true);
        item.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showDetails(timestamp, command, cwdBefore, cwdAfter, stdout, stderr, error, exitCode, duration);
            }
        });
        container.addView(item);
    }

    private String formatSummary(long timestamp, String command, String cwdAfter, int exitCode, long duration) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timestamp));
        return time + "\n" +
                "exit=" + exitCode + "  " + duration + " ms\n" +
                cwdAfter + "\n$ " + command;
    }

    private void showDetails(long timestamp, String command, String cwdBefore, String cwdAfter,
                             String stdout, String stderr, String error, int exitCode, long duration) {
        StringBuilder b = new StringBuilder();
        b.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timestamp))).append('\n');
        b.append("exitCode: ").append(exitCode).append('\n');
        b.append("durasi: ").append(duration).append(" ms\n");
        b.append("cwd sebelum: ").append(cwdBefore).append('\n');
        b.append("cwd sesudah: ").append(cwdAfter).append('\n');
        b.append("\n$ ").append(command).append('\n');
        b.append("\n--- stdout ---\n").append(stdout == null ? "" : stdout);
        b.append("\n\n--- stderr ---\n").append(stderr == null ? "" : stderr);
        if (!TextUtils.isEmpty(error)) b.append("\n\n--- error internal ---\n").append(error);

        TextView text = new TextView(this);
        text.setText(b.toString());
        text.setTextIsSelectable(true);
        text.setTextSize(13);
        text.setTypeface(Typeface.MONOSPACE);
        ScrollView scroll = new ScrollView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        scroll.setPadding(pad, pad, pad, pad);
        scroll.addView(text);
        new AlertDialog.Builder(this)
                .setTitle("Detail log terminal")
                .setView(scroll)
                .setPositiveButton("Tutup", null)
                .show();
    }

    private void confirmClear() {
        if (db.countLogs() == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("Hapus histori terminal?")
                .setMessage("Semua log terminal di database aplikasi akan dihapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        db.clearLogs();
                        loadLogs();
                    }
                })
                .show();
    }

    private String get(Cursor c, String column) {
        String value = c.getString(c.getColumnIndexOrThrow(column));
        return value == null ? "" : value;
    }
}
