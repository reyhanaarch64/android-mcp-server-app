package mcp.android.phone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class ScreenCaptureActivity extends Activity {
    private static final int REQUEST_CAPTURE = 8201;

    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("Akses Tangkapan Layar");
        title.setTextSize(22);
        title.setTextColor(Color.BLACK);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView info = new TextView(this);
        info.setText("Akses ini diperlukan agar Phone To MCP dapat mengambil tangkapan layar ketika tool MCP dipanggil.");
        info.setTextSize(15);
        info.setPadding(0, 24, 0, 24);
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));

        Button grant = new Button(this);
        grant.setText("Berikan Akses Tangkapan Layar");
        grant.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                    startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
                } catch (Exception e) {
                    Toast.makeText(ScreenCaptureActivity.this, "Gagal meminta akses: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        root.addView(grant, new LinearLayout.LayoutParams(-1, -2));

        Button clear = new Button(this);
        clear.setText("Hapus Akses Saat Ini");
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ScreenCaptureManager.clear();
                Toast.makeText(ScreenCaptureActivity.this, "Akses tangkapan layar dihentikan.", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        root.addView(clear, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Akses tangkapan layar tidak diberikan.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ScreenCaptureManager.setProjection(this, resultCode, data);
            Toast.makeText(this, "Akses tangkapan layar aktif.", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal menyimpan akses: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
