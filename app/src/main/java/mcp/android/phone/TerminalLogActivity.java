package mcp.android.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;

public class TerminalLogActivity extends Activity {
    private LinearLayout entries;
    private TextView summary;
    private TerminalLogDb db;

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new TerminalLogDb(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);
        root.setBackgroundColor(Color.rgb(12, 15, 18));

        TextView title = new TextView(this); title.setText("Log Terminal"); title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setTextColor(Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        summary = new TextView(this); summary.setTextColor(Color.LTGRAY); summary.setTextSize(12);
        summary.setPadding(0, 4, 0, 8); root.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = new Button(this); refresh.setText("Muat ulang");
        refresh.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { refreshLogs(); } });
        Button clear = new Button(this); clear.setText("Bersihkan database");
        clear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { confirmClear(); } });
        Button back = new Button(this); back.setText("Kembali");
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        buttons.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
        buttons.addView(clear, new LinearLayout.LayoutParams(0, -2, 1));
        buttons.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        entries = new LinearLayout(this); entries.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(entries, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollParams.topMargin = 8; root.addView(scroll, scrollParams);
        setContentView(root);
        refreshLogs();
    }

    private void refreshLogs() {
        entries.removeAllViews();
        ArrayList<String> logs = db.readFormatted(200);
        summary.setText("terminal.db • " + logs.size() + " entri terakhir ditampilkan");
        if (logs.size() == 0) {
            TextView empty = new TextView(this); empty.setText("Belum ada command yang dicatat.");
            empty.setTextColor(Color.LTGRAY); empty.setPadding(4, 20, 4, 20); entries.addView(empty); return;
        }
        for (int i = 0; i < logs.size(); i++) {
            TextView item = new TextView(this); item.setText(logs.get(i)); item.setTextSize(12);
            item.setTextColor(Color.rgb(190, 235, 190)); item.setTypeface(Typeface.MONOSPACE);
            item.setTextIsSelectable(true); item.setPadding(8, 10, 8, 10);
            item.setBackgroundColor(i % 2 == 0 ? Color.rgb(22, 27, 31) : Color.rgb(17, 21, 24));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 6;
            entries.addView(item, lp);
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this).setTitle("Bersihkan log terminal?")
                .setMessage("Semua riwayat command di terminal.db akan dihapus. Tindakan ini tidak bisa dibatalkan.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Bersihkan", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        int count = db.clearLogs(); refreshLogs();
                        Toast.makeText(TerminalLogActivity.this, count + " entri dihapus", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    protected void onDestroy() { if (db != null) db.close(); super.onDestroy(); }
}
