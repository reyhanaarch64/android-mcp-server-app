package mcp.android.phone;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CrashLogger {
    public static final String CRASH_LOG_FILE = "/data/local/tmp/phone_mcp_crash.log";

    public static void logEvent(Context context, String tag, String message) {
        if (context == null || message == null || message.length() == 0) {
            return;
        }
        try {
            File file = new File(CRASH_LOG_FILE);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String line = stamp + " [" + tag + "] " + message + "\n";
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                out.write(line.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (IOException e) {
            Log.e("CrashLogger", "gagal menulis log", e);
        }
    }

    public static void logCrash(Context context, Throwable throwable) {
        if (throwable == null) {
            return;
        }
        String tag = "PhoneMcpCrash";
        StringBuilder sb = new StringBuilder();
        sb.append("error=").append(throwable.getClass().getName()).append("\n");
        sb.append("message=").append(throwable.getMessage()).append("\n");
        sb.append("stack=\n");
        StackTraceElement[] stack = throwable.getStackTrace();
        if (stack != null) {
            for (int i = 0; i < stack.length; i++) {
                sb.append(stack[i].toString()).append("\n");
            }
        }
        logEvent(context, tag, sb.toString());
    }
}
