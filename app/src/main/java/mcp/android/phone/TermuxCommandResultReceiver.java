package mcp.android.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public class TermuxCommandResultReceiver extends BroadcastReceiver {
    public static final String EXTRA_EXECUTION_ID = "phone_to_mcp_execution_id";

    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Bundle result = intent.getBundleExtra("result");
        int executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, 0);
        TerminalExecutor.deliverResult(executionId, result);
    }
}
