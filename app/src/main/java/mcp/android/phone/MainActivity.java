package mcp.android.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.content.res.ColorStateList;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 7009;
    private static final int TAB_DASHBOARD = 0;
    private static final int TAB_TOOLS = 1;
    private static final int TAB_ACCESS = 2;

    private LinearLayout toolContainer;
    private LinearLayout dashboardPanel;
    private LinearLayout toolsPanel;
    private LinearLayout accessPanel;
    private TextView statusText;
    private TextView publicUrlText;
    private TextView localUrlText;
    private TextView tokenText;
    private Switch authSwitch;
    private Button tabDashboard;
    private Button tabTools;
    private Button tabAccess;
    private Button serverToggleButton;
    private Switch themeSwitch;
    private boolean darkMode;
    private boolean lastTermuxInstalled;
    private Typeface faSolidTypeface;
    private Typeface faRegularTypeface;
    private Typeface faBrandsTypeface;
    private final Handler statusHandler = new Handler();
    private final Runnable statusPoll = new Runnable() {
        public void run() {
            refreshStatus();
            statusHandler.postDelayed(this, 1500L);
        }
    };

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CrashDiagnostics.checkPreviousSession(this);
        setContentView(R.layout.main);
        loadFontAwesome();

        statusText = (TextView) findViewById(R.id.statusText);
        publicUrlText = (TextView) findViewById(R.id.publicUrlText);
        localUrlText = (TextView) findViewById(R.id.localUrlText);
        tokenText = (TextView) findViewById(R.id.tokenText);
        toolContainer = (LinearLayout) findViewById(R.id.toolContainer);
        dashboardPanel = (LinearLayout) findViewById(R.id.dashboardPanel);
        toolsPanel = (LinearLayout) findViewById(R.id.toolsPanel);
        accessPanel = (LinearLayout) findViewById(R.id.accessPanel);
        authSwitch = (Switch) findViewById(R.id.authSwitch);
        tabDashboard = (Button) findViewById(R.id.tabDashboard);
        tabTools = (Button) findViewById(R.id.tabTools);
        tabAccess = (Button) findViewById(R.id.tabAccess);
        serverToggleButton = (Button) findViewById(R.id.serverToggleButton);
        themeSwitch = (Switch) findViewById(R.id.themeSwitch);
        darkMode = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("dark_mode", false);

        styleCard(findViewById(R.id.serverCard));
        styleCard(findViewById(R.id.tokenCard));
        styleCard(findViewById(R.id.toolActionsCard));
        styleCard(findViewById(R.id.permissionsCard));
        styleTabButtons();
        setIconText((TextView) findViewById(R.id.headerBadgeText), faBrandsTypeface, "\uf17b", "MCP");
        setIconText((TextView) findViewById(R.id.toolHelpText), faRegularTypeface, "\uf059", "Atur tool yang boleh dipanggil melalui MCP. Tool penulisan dan kontrol perangkat sebaiknya diaktifkan sesuai kebutuhan.");
        setFontAwesomeButton((Button) findViewById(R.id.localUrlCopyButton), "\uf0c5", "Salin URL lokal");
        setFontAwesomeButton((Button) findViewById(R.id.publicUrlCopyButton), "\uf0c5", "Salin URL tunnel");
        setFontAwesomeButton((Button) findViewById(R.id.tokenCopyButton), "\uf0c5", "Salin token");
        updateServerToggleButton();
        serverToggleButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (isServiceRunning()) stopServer();
                else startServer();
            }
        });
        themeSwitch.setChecked(darkMode);
        themeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                darkMode = checked;
                getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("dark_mode", checked).apply();
                applyTheme();
            }
        });
        View terminalLogButton = findViewById(R.id.terminalLogButton);
        lastTermuxInstalled = TerminalExecManager.isInstalled(this);
        terminalLogButton.setVisibility(lastTermuxInstalled ? View.VISIBLE : View.GONE);
        terminalLogButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, TerminalLogActivity.class));
            }
        });
        findViewById(R.id.localUrlCopyButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyText("URL lokal", localUrlText.getText().toString()); }
        });
        findViewById(R.id.publicUrlCopyButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String value = publicUrlText.getText().toString();
                if (value.startsWith("https://")) copyText("URL tunnel", value);
                else Toast.makeText(MainActivity.this, "URL tunnel belum tersedia.", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.tokenCopyButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyText("Token MCP", tokenText.getText().toString()); }
        });
        findViewById(R.id.permissionsButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { PermissionUtil.requestRuntime(MainActivity.this, REQUEST_PERMISSIONS); }
        });
        findViewById(R.id.allOnButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ToolSettings.enableAll(MainActivity.this);
                refreshTools();
                Toast.makeText(MainActivity.this, "Semua tool diaktifkan.", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.allOffButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ToolSettings.disableAll(MainActivity.this);
                refreshTools();
                Toast.makeText(MainActivity.this, "Semua tool dinonaktifkan.", Toast.LENGTH_SHORT).show();
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
            public void onClick(View v) {
                updateToken(true);
                Toast.makeText(MainActivity.this, "Token MCP baru dibuat.", Toast.LENGTH_SHORT).show();
            }
        });

        tabDashboard.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTab(TAB_DASHBOARD); }
        });
        tabTools.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTab(TAB_TOOLS); }
        });
        tabAccess.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTab(TAB_ACCESS); }
        });

        authSwitch.setChecked(ToolSettings.authEnabled(this));
        authSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                ToolSettings.setAuthEnabled(MainActivity.this, checked);
            }
        });

        updateToken(false);
        refreshTools();
        applyTheme();
        showTab(TAB_DASHBOARD);
        refreshStatus();
    }

    protected void onResume() {
        super.onResume();
        boolean termuxInstalled = TerminalExecManager.isInstalled(this);
        if (termuxInstalled != lastTermuxInstalled) {
            lastTermuxInstalled = termuxInstalled;
            View terminalLogButton = findViewById(R.id.terminalLogButton);
            if (terminalLogButton != null) terminalLogButton.setVisibility(termuxInstalled ? View.VISIBLE : View.GONE);
            refreshTools();
        }
        refreshStatus();
        statusHandler.removeCallbacks(statusPoll);
        statusHandler.post(statusPoll);
    }

    protected void onPause() {
        statusHandler.removeCallbacks(statusPoll);
        super.onPause();
    }

    private void loadFontAwesome() {
        try { faSolidTypeface = Typeface.createFromAsset(getAssets(), "fontawesome/fa-solid-900.ttf"); }
        catch (Exception ignored) { faSolidTypeface = null; }
        try { faRegularTypeface = Typeface.createFromAsset(getAssets(), "fontawesome/fa-regular-400.ttf"); }
        catch (Exception ignored) { faRegularTypeface = null; }
        try { faBrandsTypeface = Typeface.createFromAsset(getAssets(), "fontawesome/fa-brands-400.ttf"); }
        catch (Exception ignored) { faBrandsTypeface = null; }
    }

    private void setFontAwesomeButton(Button button, String glyph, String label) {
        if (button == null) return;
        button.setTypeface(Typeface.DEFAULT);
        setIconText(button, faSolidTypeface, glyph, label);
    }

    private void setIconText(TextView view, Typeface iconTypeface, String glyph, String label) {
        if (view == null) return;
        view.setTypeface(Typeface.DEFAULT);
        if (iconTypeface == null) {
            view.setText(label);
            return;
        }
        SpannableString text = new SpannableString(glyph + "  " + label);
        text.setSpan(new IconTypefaceSpan(iconTypeface), 0, glyph.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text);
    }

    private static class IconTypefaceSpan extends MetricAffectingSpan {
        private final Typeface typeface;
        IconTypefaceSpan(Typeface typeface) { this.typeface = typeface; }
        public void updateDrawState(TextPaint paint) { paint.setTypeface(typeface); }
        public void updateMeasureState(TextPaint paint) { paint.setTypeface(typeface); }
    }

    private void styleCard(View view) {
        if (view == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setColor(darkMode ? Color.rgb(17, 17, 17) : Color.WHITE);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), darkMode ? Color.rgb(42, 42, 42) : Color.rgb(229, 233, 241));
        view.setBackgroundDrawable(background);
        if (Build.VERSION.SDK_INT >= 21) view.setElevation(dp(2));
    }

    private void styleTabButtons() {
        styleTab(tabDashboard, true);
        styleTab(tabTools, false);
        styleTab(tabAccess, false);
    }

    private void styleTab(Button button, boolean selected) {
        if (button == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(12));
        background.setColor(selected
                ? (darkMode ? Color.rgb(42, 42, 42) : Color.rgb(45, 103, 190))
                : Color.TRANSPARENT);
        button.setBackgroundDrawable(background);
        button.setTextColor(selected ? Color.WHITE : (darkMode ? Color.rgb(165, 165, 165) : Color.rgb(84, 98, 119)));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void showTab(int tab) {
        dashboardPanel.setVisibility(tab == TAB_DASHBOARD ? View.VISIBLE : View.GONE);
        toolsPanel.setVisibility(tab == TAB_TOOLS ? View.VISIBLE : View.GONE);
        accessPanel.setVisibility(tab == TAB_ACCESS ? View.VISIBLE : View.GONE);
        styleTab(tabDashboard, tab == TAB_DASHBOARD);
        styleTab(tabTools, tab == TAB_TOOLS);
        styleTab(tabAccess, tab == TAB_ACCESS);
        findViewById(R.id.contentScroll).scrollTo(0, 0);
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
        CrashDiagnostics.markNormalStop(this, "tombol Stop Server ditekan");
        ToolSettings.setServerEnabled(this, false);
        try { stopService(new Intent(this, PhoneMcpService.class)); } catch (Exception ignored) { }
        getSharedPreferences("server", MODE_PRIVATE).edit()
                .remove("public_url").remove("tunnel_timeout").remove("tunnel_error").apply();
        Toast.makeText(this, "Server dihentikan", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void updateToken(boolean regenerate) {
        String token = regenerate ? ToolSettings.regenerateToken(this) : ToolSettings.token(this);
        tokenText.setText(token);
    }

    private void refreshStatus() {
        boolean desired = ToolSettings.serverEnabled(this);
        boolean running = isServiceRunning();
        if (running) {
            statusText.setText("Server aktif");
            statusText.setTextColor(Color.rgb(48, 190, 128));
        } else if (desired) {
            statusText.setText("Menunggu pemulihan server");
            statusText.setTextColor(Color.rgb(224, 164, 75));
        } else {
            statusText.setText("Server dimatikan");
            statusText.setTextColor(darkMode ? Color.rgb(185, 197, 214) : Color.rgb(80, 91, 108));
        }
        updateServerToggleButton();
        localUrlText.setText("http://127.0.0.1:9009/mcp");

        String publicUrl = getSharedPreferences("server", MODE_PRIVATE).getString("public_url", null);
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
        if (am == null) return false;
        List<android.app.ActivityManager.RunningServiceInfo> services = am.getRunningServices(100);
        for (android.app.ActivityManager.RunningServiceInfo service : services) {
            if (PhoneMcpService.class.getName().equals(service.service.getClassName())) return true;
        }
        return false;
    }

    private void refreshTools() {
        toolContainer.removeAllViews();
        LinkedHashMap<String, String> names = new LinkedHashMap<String, String>();
        names.put("device", "Perangkat dan spesifikasi");
        names.put("network", "Jaringan");
        names.put("download", "Unduhan");
        names.put("files", "File dan penyimpanan");
        names.put("json", "JSON Tools");
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
        if (TerminalExecManager.isInstalled(this)) names.put("terminal", "Terminal / Termux");

        for (Map.Entry<String, String> entry : names.entrySet()) addCategory(entry.getKey(), entry.getValue());
    }

    private void addCategory(final String category, String title) {
        List<ToolDef> allTools = ToolRegistry.all();
        int count = 0;
        for (int i = 0; i < allTools.size(); i++) {
            if (category.equals(allTools.get(i).category)) count++;
        }
        if (count == 0) return;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(10));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(10);
        styleCard(card);
        toolContainer.addView(card, cardParams);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextSize(15);
        header.setTextColor(primaryTextColor());
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(header, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView counter = new TextView(this);
        counter.setText(String.valueOf(count));
        counter.setTextSize(11);
        counter.setTextColor(darkMode ? Color.rgb(225, 225, 225) : Color.rgb(49, 94, 173));
        counter.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        counter.setGravity(Gravity.CENTER);
        GradientDrawable counterBg = new GradientDrawable();
        counterBg.setColor(darkMode ? Color.rgb(35, 35, 35) : Color.rgb(233, 240, 252));
        counterBg.setCornerRadius(dp(20));
        counter.setBackgroundDrawable(counterBg);
        counter.setPadding(dp(9), dp(4), dp(9), dp(4));
        heading.addView(counter);
        card.addView(heading);

        View divider = new View(this);
        divider.setBackgroundColor(darkMode ? Color.rgb(51, 65, 85) : Color.rgb(232, 236, 243));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(10);
        dividerParams.bottomMargin = dp(4);
        card.addView(divider, dividerParams);

        for (int i = 0; i < allTools.size(); i++) {
            final ToolDef tool = allTools.get(i);
            if (!category.equals(tool.category)) continue;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(tool.name);
            name.setTextSize(13);
            name.setTextColor(primaryTextColor());
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            labels.addView(name);
            TextView description = new TextView(this);
            description.setText(tool.description);
            description.setTextSize(10);
            description.setTextColor(secondaryTextColor());
            description.setMaxLines(2);
            description.setPadding(0, dp(2), 0, 0);
            labels.addView(description);
            row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            Switch toggle = new Switch(this);
            toggle.setChecked(ToolSettings.isEnabled(this, tool));
            toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton button, boolean checked) {
                    ToolSettings.setTool(MainActivity.this, tool, checked);
                }
            });
            row.addView(toggle);
            card.addView(row);

            if (i < allTools.size() - 1) {
                boolean hasNextInCategory = false;
                for (int j = i + 1; j < allTools.size(); j++) {
                    if (category.equals(allTools.get(j).category)) { hasNextInCategory = true; break; }
                }
                if (hasNextInCategory) {
                    View line = new View(this);
                    line.setBackgroundColor(darkMode ? Color.rgb(43, 54, 72) : Color.rgb(241, 243, 247));
                    card.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
                }
            }
        }
    }

    private int primaryTextColor() {
        return darkMode ? Color.rgb(242, 242, 242) : Color.rgb(39, 51, 70);
    }

    private int secondaryTextColor() {
        return darkMode ? Color.rgb(165, 165, 165) : Color.rgb(112, 123, 141);
    }

    private void updateServerToggleButton() {
        if (serverToggleButton == null) return;
        boolean running = isServiceRunning();
        String glyph = running ? "\uf04d" : "\uf04b";
        String label = running ? "Stop server" : "Start server";
        setFontAwesomeButton(serverToggleButton, glyph, label);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(running ? Color.rgb(176, 55, 64) : Color.rgb(39, 119, 82));
        serverToggleButton.setBackgroundDrawable(bg);
        serverToggleButton.setTextColor(Color.WHITE);
    }

    private void applyTheme() {
        int background = darkMode ? Color.BLACK : Color.rgb(243, 245, 249);
        int panel = darkMode ? Color.BLACK : Color.WHITE;
        int headerColor = darkMode ? Color.BLACK : Color.rgb(23, 32, 51);
        View root = findViewById(R.id.root);
        if (root != null) root.setBackgroundColor(background);
        View scroll = findViewById(R.id.contentScroll);
        if (scroll != null) scroll.setBackgroundColor(background);

        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            if (group.getChildCount() > 0) group.getChildAt(0).setBackgroundColor(headerColor);
            if (group.getChildCount() > 1) group.getChildAt(1).setBackgroundColor(panel);
            if (group.getChildCount() > 2) group.getChildAt(2).setBackgroundColor(background);
        }

        styleCard(findViewById(R.id.serverCard));
        styleCard(findViewById(R.id.tokenCard));
        styleCard(findViewById(R.id.toolActionsCard));
        styleCard(findViewById(R.id.permissionsCard));
        localUrlText.setTextColor(secondaryTextColor());
        publicUrlText.setTextColor(primaryTextColor());
        tokenText.setTextColor(primaryTextColor());
        themeSwitch.setTextColor(Color.WHITE);

        TextView headerBadge = (TextView) findViewById(R.id.headerBadgeText);
        if (headerBadge != null) {
            GradientDrawable badge = new GradientDrawable();
            badge.setCornerRadius(dp(8));
            badge.setColor(darkMode ? Color.rgb(35, 35, 35) : Color.rgb(43, 61, 91));
            headerBadge.setBackgroundDrawable(badge);
        }

        if (Build.VERSION.SDK_INT >= 21) {
            int accent = darkMode ? Color.rgb(220, 220, 220) : Color.rgb(49, 94, 173);
            themeSwitch.setThumbTintList(ColorStateList.valueOf(accent));
            themeSwitch.setTrackTintList(ColorStateList.valueOf(
                    darkMode ? Color.rgb(90, 90, 90) : Color.rgb(180, 194, 214)));
            getWindow().setStatusBarColor(headerColor);
            getWindow().setNavigationBarColor(darkMode ? Color.BLACK : Color.rgb(23, 32, 51));
            if (Build.VERSION.SDK_INT >= 26) {
                getWindow().getDecorView().setSystemUiVisibility(0);
            }
        }

        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            if (group.getChildCount() > 0 && group.getChildAt(0) instanceof ViewGroup) {
                applyTextColors((ViewGroup) group.getChildAt(0), true);
            }
            if (group.getChildCount() > 2 && group.getChildAt(2) instanceof ViewGroup) {
                applyTextColors((ViewGroup) group.getChildAt(2), false);
            }
        }
        styleTab(tabDashboard, dashboardPanel.getVisibility() == View.VISIBLE);
        styleTab(tabTools, toolsPanel.getVisibility() == View.VISIBLE);
        styleTab(tabAccess, accessPanel.getVisibility() == View.VISIBLE);
        updateServerToggleButton();
        refreshStatus();
    }

    private void applyTextColors(ViewGroup group, boolean keepHeaderColors) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                TextView text = (TextView) child;
                if (child == statusText) continue;
                if (child == tabDashboard || child == tabTools || child == tabAccess || child == serverToggleButton) continue;
                if (keepHeaderColors) continue;
                float size = text.getTextSize() / getResources().getDisplayMetrics().scaledDensity;
                text.setTextColor(size <= 11 ? secondaryTextColor() : primaryTextColor());
                if (child instanceof Button && Build.VERSION.SDK_INT >= 21) {
                    ((Button) child).setBackgroundTintList(ColorStateList.valueOf(
                            darkMode ? Color.rgb(38, 38, 38) : Color.rgb(229, 235, 244)));
                }
            }
            if (child instanceof ViewGroup) applyTextColors((ViewGroup) child, keepHeaderColors);
        }
    }

    private void refreshScreenCaptureButton() {
        View view = findViewById(R.id.screenCaptureButton);
        if (view instanceof Button) {
            ((Button) view).setText(ScreenCaptureManager.isReady() ? "Hapus akses tangkapan layar" : "Berikan akses tangkapan layar");
        }
    }

    private void copyText(String label, String value) {
        if (value == null || value.length() == 0) {
            Toast.makeText(this, "Tidak ada teks untuk disalin.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Clipboard tidak tersedia.", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, label + " disalin.", Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
