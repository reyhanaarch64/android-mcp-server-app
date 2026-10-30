package mcp.android.phone;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class CopyUrlReceiver extends BroadcastReceiver {
    public static final String ACTION_COPY_URL = "mcp.android.phone.action.COPY_NOTIFICATION_URL";
    public static final String EXTRA_URL = "copy_url";

    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_COPY_URL.equals(intent.getAction())) return;
        String url = intent.getStringExtra(EXTRA_URL);
        if (url == null || url.length() == 0) return;
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("MCP URL", url));
        Toast.makeText(context, "URL disalin", Toast.LENGTH_SHORT).show();
    }
}
