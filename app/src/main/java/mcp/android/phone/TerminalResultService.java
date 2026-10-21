package mcp.android.phone;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class TerminalResultService extends IntentService {
    public static final String EXTRA_EXECUTION_ID = "mcp.android.phone.extra.TERMINAL_EXECUTION_ID";
    private static final String TAG = "TerminalResult";
    public TerminalResultService() { super("TerminalResultService"); }

    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;
        long id = intent.getLongExtra(EXTRA_EXECUTION_ID, -1L);
        Bundle result = intent.getBundleExtra("com.termux.app.extra.PLUGIN_RESULT_BUNDLE");
        if (result == null) result = intent.getBundleExtra("com.termux.execute.plugin_result_bundle");
        if (result == null && intent.getExtras() != null) {
            for (String key : intent.getExtras().keySet()) {
                Object value = intent.getExtras().get(key);
                if (value instanceof Bundle) {
                    Bundle candidate = (Bundle) value;
                    if (containsKeyPart(candidate, "STDOUT") || containsKeyPart(candidate, "EXIT_CODE")) { result = candidate; break; }
                }
            }
        }
        if (id <= 0) { Log.e(TAG, "Hasil Termux tidak memiliki execution id"); return; }
        if (result == null) {
            TerminalExecManager.receive(this, id, "", "", -1, false, "Termux tidak mengirim result bundle.");
            return;
        }
        String stdout = getStringPart(result, "STDOUT", "");
        String stderr = getStringPart(result, "STDERR", "");
        int exitCode = getIntPart(result, "EXIT_CODE", -1);
        int err = getIntPart(result, "_ERR", -1);
        String errmsg = getStringPart(result, "ERRMSG", "");
        boolean completed = err == -1;
        String error = completed ? "" : (errmsg.length() == 0 ? "Termux internal error: " + err : errmsg);
        TerminalExecManager.receive(this, id, stdout, stderr, exitCode, completed, error);
    }

    private static boolean containsKeyPart(Bundle b, String part) {
        for (String key : b.keySet()) if (key.toUpperCase(java.util.Locale.US).contains(part)) return true;
        return false;
    }
    private static String getStringPart(Bundle b, String part, String fallback) {
        for (String key : b.keySet()) {
            String upper = key.toUpperCase(java.util.Locale.US);
            if (upper.contains(part) && !upper.contains("ORIGINAL_LENGTH")) {
                Object value = b.get(key); if (value instanceof String) return (String) value;
            }
        }
        return fallback;
    }
    private static int getIntPart(Bundle b, String part, int fallback) {
        for (String key : b.keySet()) {
            String upper = key.toUpperCase(java.util.Locale.US);
            boolean match = upper.endsWith(part) || upper.contains(part);
            if ("_ERR".equals(part)) match = "ERR".equals(upper) || upper.endsWith(".ERR") || upper.endsWith("_ERR");
            if (match) {
                Object value = b.get(key);
                if (value instanceof Integer) return ((Integer) value).intValue();
                if (value instanceof String) try { return Integer.parseInt((String) value); } catch (Exception ignored) { }
            }
        }
        return fallback;
    }
}
