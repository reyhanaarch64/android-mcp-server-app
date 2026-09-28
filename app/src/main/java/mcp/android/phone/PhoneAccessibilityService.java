package mcp.android.phone;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

public class PhoneAccessibilityService extends AccessibilityService {
    public static volatile PhoneAccessibilityService INSTANCE;

    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
    }

    protected void onServiceConnected() {
        INSTANCE = this;
    }

    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    public void onInterrupt() {
    }

    public void onDestroy() {
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }
}
