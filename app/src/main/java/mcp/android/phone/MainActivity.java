package mcp.android.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 7009;
    private LinearLayout toolContainer;
    private TextView statusText;
    private TextView publicUrlText;
    private TextView tokenText;
    private Switch authSwitch;
    private final Handler statusHandler = new Handler();
    private final Runnable statusPoll = new Runnable() {
        public void run() {
            refreshStatus();
            statusHandler.postDelayed(this, 1000L);
        }
    };

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);
        statusText = (TextView) findViewById(R.id.statusText);
        publicUrlText = (TextView) findViewById(R.id.publicUrlText);
        tokenText = (TextView) findViewById(R.id.tokenText);
        toolContainer = (LinearLayout) findViewById(R.id.toolContainer);
        authSwitch = (Switch) findViewById(R.id.authSwitch);

        findViewById(R.id.startButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startServer(); }
        });
        findViewById(R.id.stopButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stopServer(); }
        });
        findViewById(R.id.permissionsButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { PermissionUtil.requestRuntime(MainActivity.this, REQUEST_PERMISSIONS); }
        });
        findViewById(R.id.allOnButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ToolSettings.enableAll(MainActivity.this);
                refreshTools();
                refreshTerminalControls();
            }
        });
        findViewById(R.id.allOffButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ToolSettings.disableAll(MainActivity.this);
                refreshTools();
                refreshTerminalControls();
            }
        });
        findViewById(R.id.batteryButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { PermissionUtil.openBattery(MainActivity.this); }
        });
        findViewById(R.id.vivoButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { PermissionUtil.openVivoAutostart(MainActivity.this); }
        });
        findViewById(R.id.storageButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { PermissionUtil.openAllFiles(MainActivity.this); }
        });
        findViewById(R.id.notificationAccessButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { PermissionUtil.openNotificationListener(MainActivity.this); }
        });
        findViewById(R.id.accessibilityButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { PermissionUtil.openAccessibility(MainActivity.this); }
        });
        findViewById(R.id.usageButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { PermissionUtil.openUsage(MainActivity.this); }
        });
        findViewById(R.id.overlayButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { PermissionUtil.openOverlay(MainActivity.this); }
        });
        findViewById(R.id.writeSettingsButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { PermissionUtil.openWriteSettings(MainActivity.this); }
        });
        findViewById(R.id.screenCaptureButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (ScreenCaptureManager.isReady()) ScreenCaptureManager.clear();
                else startActivity(new Intent(MainActivity.this, ScreenCaptureActivity.class));
                refreshScreenCaptureButton();
            }
        });
        findViewById(R.id.regenerateTokenButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { updateToken(true); }
        });
        publicUrlText.setTextIsSelectable(true);
        publicUrlText.setClickable(true);
        publicUrlText.setTextColor(android.graphics.Color.rgb(26, 115, 232));
        publicUrlText.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String url = publicUrlText.getText().toString();
                if (url.startsWith("https://")) {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Cloudflare MCP URL", url));
                    Toast.makeText(MainActivity.this, "URL tunnel disalin ke clipboard.", Toast.LENGTH_SHORT).show();
                }
            }
        });
        authSwitch.setChecked(ToolSettings.authEnabled(this));
        authSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton button, boolean checked) { ToolSettings.setAuthEnabled(MainActivity.this, checked); }
        });

        final Switch terminalSwitch = (Switch) findViewById(R.id.terminalSwitch);
        terminalSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (!TerminalExecutor.isTermuxInstalled(MainActivity.this)) {
                    button.setChecked(false);
                    ToolSettings.setTerminalEnabled(MainActivity.this, false);
                    ToolSettings.setTool(MainActivity.this, ToolRegistry.get("terminal.exec"), false);
                    refreshTerminalControls();
                    return;
                }
                ToolSettings.setTerminalEnabled(MainActivity.this, checked);
                ToolSettings.setTool(MainActivity.this, ToolRegistry.get("terminal.exec"), checked);
                refreshTools();
                refreshTerminalControls();
            }
        });
        findViewById(R.id.terminalLogsButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, TerminalLogActivity.class));
            }
        });
        findViewById(R.id.terminalPermissionButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Pengaturan aplikasi tidak dapat dibuka.", Toast.LENGTH_SHORT).show();
                }
            }
        });
        updateToken(false);
        ToolSettings.syncTermuxAvailability(this);
        refreshTools();
        refreshTerminalControls();
    }

    protected void onResume() {
        super.onResume();
        ToolSettings.syncTermuxAvailability(this);
        refreshStatus();
        refreshTerminalControls();
        statusHandler.removeCallbacks(statusPoll);
        statusHandler.post(statusPoll);
    }

    protected void onPause() {
        statusHandler.removeCallbacks(statusPoll);
        super.onPause();
    }

    private void startServer() {
        ToolSettings.setServerEnabled(this, true);
        Intent i = new Intent(this, PhoneMcpService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            Toast.makeText(this, "Server dimulai pada port 9009", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            ToolSettings.setServerEnabled(this, false);
            Toast.makeText(this, "Server gagal dimulai: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        refreshStatus();
    }

    private void stopServer() {
        ToolSettings.setServerEnabled(this, false);
        try { stopService(new Intent(this, PhoneMcpService.class)); } catch (Exception ignored) { }
        getSharedPreferences("server", MODE_PRIVATE).edit().remove("public_url").remove("tunnel_timeout").remove("tunnel_error").apply();
        Toast.makeText(this, "Server dihentikan", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void updateToken(boolean regenerate) {
        String token = regenerate ? ToolSettings.regenerateToken(this) : ToolSettings.token(this);
        tokenText.setText("Token MCP: " + token + "\nGunakan Authorization: Bearer <token> pada client MCP.");
    }

    private void refreshTerminalControls() {
        ToolSettings.syncTermuxAvailability(this);
        boolean installed = TerminalExecutor.isTermuxInstalled(this);
        boolean enabled = ToolSettings.terminalEnabled(this) && installed;
        boolean permission = installed && TerminalExecutor.hasRunCommandPermission(this);

        Switch terminalSwitch = (Switch) findViewById(R.id.terminalSwitch);
        TextView terminalStatus = (TextView) findViewById(R.id.terminalStatusText);
        Button permissionButton = (Button) findViewById(R.id.terminalPermissionButton);

        terminalSwitch.setEnabled(installed);
        terminalSwitch.setChecked(enabled);
        permissionButton.setEnabled(installed);
        if (!installed) {
            terminalStatus.setText("Termux tidak terpasang. Eksekusi terminal otomatis dinonaktifkan.");
        } else if (!enabled) {
            terminalStatus.setText("Termux tersedia. Eksekusi terminal sedang nonaktif.");
        } else if (!permission) {
            terminalStatus.setText("Termux tersedia, tetapi izin Run Command belum diberikan ke Phone To MCP.");
        } else {
            terminalStatus.setText("Termux tersedia dan eksekusi terminal aktif.");
        }
    }

    private void refreshStatus() {
        boolean desired = ToolSettings.serverEnabled(this);
        boolean running = isServiceRunning();
        if (running) {
            statusText.setText("Server aktif");
        } else if (desired) {
            statusText.setText("Server tidak aktif, mencoba memulihkan");
        } else {
            statusText.setText("Server dimatikan");
        }
        TextView localText = (TextView) findViewById(R.id.localUrlText);
        localText.setText(running ? "Lokal: http://127.0.0.1:9009/mcp" : "Lokal: server tidak aktif");

        String publicUrl = getSharedPreferences("server", MODE_PRIVATE)
                .getString("public_url", null);
        if (!running) {
            publicUrlText.setText(desired ? "Menunggu pemulihan server" : "Server dimatikan");
        } else if (publicUrl == null || publicUrl.length() == 0) {
            publicUrlText.setText("URL tunnel belum tersedia");
        } else {
            publicUrlText.setText(publicUrl);
        }
        refreshScreenCaptureButton();
    }

    private boolean isServiceRunning() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningServiceInfo> services = am.getRunningServices(100);
        for (android.app.ActivityManager.RunningServiceInfo s : services) {
            if (PhoneMcpService.class.getName().equals(s.service.getClassName())) return true;
        }
        return false;
    }

private void refreshTools() {
    toolContainer.removeAllViews();
    LinkedHashMap<String, String> names = new LinkedHashMap<String, String>();
    names.put("device", "Perangkat dan spesifikasi");
    names.put("network", "Jaringan");
    names.put("download", "Unduhan");
    names.put("files", "Penyimpanan dan editor");
    names.put("contacts", "Kontak");
    names.put("sms", "SMS");
    names.put("calllog", "Riwayat panggilan");
    names.put("phone", "Telepon");
    names.put("calendar", "Kalender");
    names.put("media", "MediaStore");
    names.put("camera", "Kamera");
    names.put("audio", "Audio");
    names.put("tts", "Text-to-Speech");
    names.put("location", "Lokasi");
    names.put("wifi", "Wi-Fi");
    names.put("bluetooth", "Bluetooth");
    names.put("apps", "Aplikasi");
    names.put("notifications", "Notifikasi");
    names.put("usage", "Usage Access");
    names.put("accessibility", "Aksesibilitas");
    names.put("clipboard", "Clipboard");
    names.put("displaywrite", "Pengaturan layar");
    names.put("ui", "UI dan URL");
    names.put("hardware", "Hardware");
    names.put("settings", "Shortcut pengaturan");
    names.put("screen", "Tangkapan layar");
    names.put("system", "Sistem");
    names.put("tiktok", "TikTok");

    for (Map.Entry<String, String> entry : names.entrySet()) addCategory(entry.getKey(), entry.getValue());
}

private void addCategory(final String category, String title) {
    TextView header = new TextView(this);
    header.setText(title);
    header.setTextSize(16);
    header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    header.setPadding(0, 18, 0, 4);
    toolContainer.addView(header);

    for (ToolDef t : ToolRegistry.all()) {
        if (!category.equals(t.category)) continue;
        final ToolDef tool = t;
        Switch s = makeSwitch(tool.name, ToolSettings.isEnabled(this, tool));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                ToolSettings.setTool(MainActivity.this, tool, checked);
            }
        });
        toolContainer.addView(s);
    }
}

private void refreshScreenCaptureButton() {
    View v = findViewById(R.id.screenCaptureButton);
    if (v instanceof Button) {
        ((Button) v).setText(ScreenCaptureManager.isReady() ? "Hapus Akses Tangkapan Layar" : "Berikan Akses Tangkapan Layar");
    }
}

    private Switch makeSwitch(String title, boolean checked) {
        Switch s = new Switch(this);
        s.setText(title);
        s.setChecked(checked);
        s.setPadding(0, 2, 0, 2);
        return s;
    }
}
