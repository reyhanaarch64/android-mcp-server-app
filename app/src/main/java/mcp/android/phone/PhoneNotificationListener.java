package mcp.android.phone;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedList;

public class PhoneNotificationListener extends NotificationListenerService {
    public static volatile PhoneNotificationListener INSTANCE;
    private final LinkedList<JSONObject> recent = new LinkedList<JSONObject>();
    private static final int MAX_RECENT = 100;

    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
    }

    public void onListenerConnected() {
        INSTANCE = this;
    }

    public void onListenerDisconnected() {
        if (INSTANCE == this) INSTANCE = null;
    }

    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        JSONObject o = toJson(sbn);
        synchronized (recent) {
            recent.addFirst(o);
            while (recent.size() > MAX_RECENT) recent.removeLast();
        }
    }

    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
    }

    public JSONArray recentJson() {
        JSONArray a = new JSONArray();
        synchronized (recent) {
            for (JSONObject o : recent) a.put(o);
        }
        return a;
    }

    private JSONObject toJson(StatusBarNotification sbn) {
        JSONObject o = new JSONObject();
        try {
            o.put("key", sbn.getKey());
            o.put("package", sbn.getPackageName());
            o.put("id", sbn.getId());
            o.put("postTime", sbn.getPostTime());
            Notification n = sbn.getNotification();
            if (n != null && n.extras != null) {
                o.put("title", String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE, "")));
                o.put("text", String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT, "")));
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    public JSONArray activeJson() {
        JSONArray a = new JSONArray();
        try {
            StatusBarNotification[] items = getActiveNotifications();
            if (items == null) return a;
            for (StatusBarNotification sbn : items) {
                JSONObject o = new JSONObject();
                try {
                    o.put("key", sbn.getKey());
                    o.put("package", sbn.getPackageName());
                    o.put("id", sbn.getId());
                    o.put("postTime", sbn.getPostTime());
                    Notification n = sbn.getNotification();
                    if (n != null && n.extras != null) {
                        o.put("title", String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE, "")));
                        o.put("text", String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT, "")));
                    }
                } catch (Exception ignored) {
                }
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        return a;
    }
}
