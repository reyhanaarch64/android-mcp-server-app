package mcp.android.phone;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class McpSupport {
    public static JSONObject obj(Object... values) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < values.length; i += 2) {
                o.put(String.valueOf(values[i]), values[i + 1]);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    public static String safeMessage(Exception e) {
        if (e == null) {
            return "Kesalahan tidak diketahui";
        }
        String msg = e.getMessage();
        if (msg == null || msg.length() == 0) {
            return e.toString();
        }
        return msg;
    }

    public static String safe(Throwable t) {
        if (t == null) {
            return "Kesalahan tidak diketahui";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        String message = t.getMessage();
        if (message == null || message.length() == 0) {
            return sw.toString();
        }
        return message + "\n" + sw.toString();
    }

    public static String join(String[] values) {
        if (values == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    public static int foregroundType(String name) {
        try {
            Field field = android.content.pm.ServiceInfo.class.getField(name);
            return field.getInt(null);
        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean invokeTypedStartForeground(Service service, int id, Notification notification, int types) {
        try {
            Method method = Service.class.getMethod("startForeground", Integer.TYPE, Notification.class, Integer.TYPE);
            method.invoke(service, Integer.valueOf(id), notification, Integer.valueOf(types));
            return true;
        } catch (Exception e) {
            Log.w("McpSupport", "typed foreground gagal, fallback", e);
            return false;
        }
    }
}
