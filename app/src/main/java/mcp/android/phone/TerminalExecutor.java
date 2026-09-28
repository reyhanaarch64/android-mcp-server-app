package mcp.android.phone;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class TerminalExecutor {
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_RUN_COMMAND_SERVICE =
	"com.termux.app.RunCommandService";
    private static final String TERMUX_ACTION_RUN_COMMAND =
	"com.termux.RUN_COMMAND";

    private static final String EXTRA_COMMAND_PATH =
	"com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS =
	"com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_STDIN =
	"com.termux.RUN_COMMAND_STDIN";
    private static final String EXTRA_WORKDIR =
	"com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND =
	"com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_PENDING_INTENT =
	"com.termux.RUN_COMMAND_PENDING_INTENT";
    private static final String EXTRA_COMMAND_LABEL =
	"com.termux.RUN_COMMAND_COMMAND_LABEL";
    private static final String EXTRA_COMMAND_DESCRIPTION =
	"com.termux.RUN_COMMAND_COMMAND_DESCRIPTION";

    private static final String RESULT_BUNDLE = "result";
    private static final String RESULT_STDOUT = "stdout";
    private static final String RESULT_STDERR = "stderr";
    private static final String RESULT_EXIT_CODE = "exitCode";
    private static final String RESULT_ERR = "err";
    private static final String RESULT_ERRMSG = "errmsg";

    private static final String MARKER_PWD = "__PHONE_TO_MCP_PWD__";
    private static final String MARKER_EXIT = "__PHONE_TO_MCP_EXIT__";

    private static final String TERMUX_HOME =
	"/data/data/com.termux/files/home";

    private static final String TERMUX_SHELL =
	"/data/data/com.termux/files/usr/bin/sh";

    private static final int MAX_COMMAND_LENGTH = 120000;
    private static final int MAX_STDIN_LENGTH = 65536;
    private static final long DEFAULT_TIMEOUT_MS = 120000L;

    /*
     * Nilai asli PendingIntent.FLAG_MUTABLE pada Android 12+.
     * Dipakai sebagai literal supaya project dengan compile sdk lama
     * tetap bisa dikompilasi oleh AIDE.
     */
    private static final int FLAG_MUTABLE = 33554432;

    private static int nextExecutionId = 1000;

    private static final Object PENDING_LOCK = new Object();
    private static final Object EXECUTION_LOCK = new Object();

    private static final HashMap<Integer, PendingResult> PENDING =
	new HashMap<Integer, PendingResult>();

    private TerminalExecutor() {
    }

    public static boolean isTermuxInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(
				TERMUX_PACKAGE,
				0
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasRunCommandPermission(Context context) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }

        try {
            return context.checkSelfPermission(
				"com.termux.permission.RUN_COMMAND"
            ) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized int nextExecutionId() {
        nextExecutionId++;

        if (nextExecutionId > 2000000000) {
            nextExecutionId = 1000;
        }

        return nextExecutionId;
    }

    public static JSONObject execute(
		Context context,
		String command,
		String requestedCwd,
		String stdin,
		int timeoutMs
    ) throws Exception {

        if (!isTermuxInstalled(context)) {
            throw new IllegalStateException(
				"Termux tidak terpasang"
            );
        }

        if (!hasRunCommandPermission(context)) {
            throw new SecurityException(
				"Izin Android com.termux.permission.RUN_COMMAND belum diberikan. " +
				"Buka Info Aplikasi Phone To MCP > Izin > Izin tambahan > " +
				"Jalankan perintah di lingkungan Termux."
            );
        }

        if (command == null || command.trim().length() == 0) {
            throw new IllegalArgumentException(
				"command wajib diisi"
            );
        }

        if (command.length() > MAX_COMMAND_LENGTH) {
            throw new IllegalArgumentException(
				"command terlalu panjang, maksimum 120000 karakter"
            );
        }

        if (stdin != null && stdin.length() > MAX_STDIN_LENGTH) {
            throw new IllegalArgumentException(
				"stdin terlalu panjang, maksimum 65536 karakter"
            );
        }

        synchronized (EXECUTION_LOCK) {
            return executeLocked(
				context,
				command,
				requestedCwd,
				stdin,
				timeoutMs
            );
        }
    }

    private static JSONObject executeLocked(
		Context context,
		String command,
		String requestedCwd,
		String stdin,
		int timeoutMs
    ) throws Exception {

        long started = System.currentTimeMillis();

        String cwdBefore = getStoredCwd(context);

        String startCwd = TextUtils.isEmpty(requestedCwd)
			? cwdBefore
			: requestedCwd.trim();

        if (startCwd.length() == 0) {
            startCwd = TERMUX_HOME;
        }

        int executionId = nextExecutionId();

        PendingResult pending = new PendingResult(executionId);

        synchronized (PENDING_LOCK) {
            PENDING.put(
				Integer.valueOf(executionId),
				pending
            );
        }

        String wrapper =
			"cd " + shellQuote(startCwd) +
			" 2>/dev/null || exit $?; " +

			"set +e; " +

			command +
			"; " +

			"rc=$?; " +

			"printf '\\n" +
			MARKER_PWD +
			"%s\\n' \"$(pwd -P)\"; " +

			"printf '" +
			MARKER_EXIT +
			"%s\\n' \"$rc\"; " +

			"exit $rc";

        Intent intent = new Intent();

        intent.setClassName(
			TERMUX_PACKAGE,
			TERMUX_RUN_COMMAND_SERVICE
        );

        intent.setAction(
			TERMUX_ACTION_RUN_COMMAND
        );

        intent.putExtra(
			EXTRA_COMMAND_PATH,
			TERMUX_SHELL
        );

        intent.putExtra(
			EXTRA_ARGUMENTS,
			new String[]{
				"-c",
				wrapper
			}
        );

        intent.putExtra(
			EXTRA_WORKDIR,
			startCwd
        );

        intent.putExtra(
			EXTRA_BACKGROUND,
			true
        );

        if (stdin != null) {
            intent.putExtra(
				EXTRA_STDIN,
				stdin
            );
        }

        intent.putExtra(
			EXTRA_COMMAND_LABEL,
			"Phone To MCP terminal"
        );

        intent.putExtra(
			EXTRA_COMMAND_DESCRIPTION,
			"Menjalankan perintah terminal melalui Termux dari Phone To MCP."
        );

        Intent resultIntent = new Intent(
			context,
			TermuxCommandResultReceiver.class
        );

        resultIntent.putExtra(
			TermuxCommandResultReceiver.EXTRA_EXECUTION_ID,
			executionId
        );

        int flags = PendingIntent.FLAG_ONE_SHOT;

        if (Build.VERSION.SDK_INT >= 31) {
            flags |= FLAG_MUTABLE;
        }

        PendingIntent resultPendingIntent =
			PendingIntent.getBroadcast(
			context,
			executionId,
			resultIntent,
			flags
		);

        intent.putExtra(
			EXTRA_PENDING_INTENT,
			resultPendingIntent
        );

        try {
            context.startService(intent);

            long waitMs;

            if (timeoutMs > 0) {
                waitMs = Math.min(
					timeoutMs,
					DEFAULT_TIMEOUT_MS
                );
            } else {
                waitMs = DEFAULT_TIMEOUT_MS;
            }

            boolean completed = pending.latch.await(
				waitMs,
				TimeUnit.MILLISECONDS
            );

            if (!completed) {
                String message =
					"waktu tunggu terminal habis setelah " +
					waitMs +
					" ms";

                saveLog(
					context,
					started,
					command,
					cwdBefore,
					cwdBefore,
					"",
					"",
					-1,
					System.currentTimeMillis() - started,
					message
                );

                return buildResult(
					command,
					cwdBefore,
					cwdBefore,
					"",
					"",
					-1,
					message,
					System.currentTimeMillis() - started,
					false
                );
            }

            String stdout =
				pending.stdout == null
				? ""
				: pending.stdout;

            String stderr =
				pending.stderr == null
				? ""
				: pending.stderr;

            int exitCode = pending.exitCode;

            String internalError =
				pending.errMsg == null
				? ""
				: pending.errMsg;

            String afterCwd =
				parseCwd(
				stdout,
				startCwd
			);

            stdout = stripMarkers(stdout);

            if (afterCwd.length() > 0) {
                setStoredCwd(
					context,
					afterCwd
                );
            }

            if (pending.errCode != Activity.RESULT_OK
				&& internalError.length() == 0) {

                internalError =
					"Termux melaporkan err=" +
					pending.errCode;
            }

            long durationMs =
				System.currentTimeMillis() - started;

            saveLog(
				context,
				started,
				command,
				cwdBefore,
				afterCwd,
				stdout,
				stderr,
				exitCode,
				durationMs,
				internalError
            );

            return buildResult(
				command,
				cwdBefore,
				afterCwd,
				stdout,
				stderr,
				exitCode,
				internalError,
				durationMs,
				true
            );

        } catch (Exception e) {

            String message =
				e.getMessage() == null
				? e.toString()
				: e.getMessage();

            saveLog(
				context,
				started,
				command,
				cwdBefore,
				cwdBefore,
				"",
				"",
				-1,
				System.currentTimeMillis() - started,
				message
            );

            throw e;

        } finally {

            synchronized (PENDING_LOCK) {
                PENDING.remove(
					Integer.valueOf(executionId)
                );
            }
        }
    }

    private static void saveLog(
		Context context,
		long timestamp,
		String command,
		String cwdBefore,
		String cwdAfter,
		String stdout,
		String stderr,
		int exitCode,
		long durationMs,
		String error
    ) {

        TerminalLogDbHelper db =
			new TerminalLogDbHelper(
			context.getApplicationContext()
		);

        try {
            db.insertLog(
				timestamp,
				command,
				cwdBefore,
				cwdAfter,
				stdout,
				stderr,
				exitCode,
				durationMs,
				error
            );
        } finally {
            db.close();
        }
    }

    private static JSONObject buildResult(
		String command,
		String cwdBefore,
		String cwdAfter,
		String stdout,
		String stderr,
		int exitCode,
		String error,
		long durationMs,
		boolean completed
    ) throws Exception {

        JSONObject o = new JSONObject();

        o.put(
			"command",
			command
        );

        o.put(
			"cwdBefore",
			cwdBefore
        );

        o.put(
			"cwd",
			cwdAfter
        );

        o.put(
			"stdout",
			stdout
        );

        o.put(
			"stderr",
			stderr
        );

        o.put(
			"exitCode",
			exitCode
        );

        o.put(
			"durationMs",
			durationMs
        );

        o.put(
			"completed",
			completed
        );

        o.put(
			"termux",
			TERMUX_PACKAGE
        );

        if (error != null
			&& error.length() > 0) {

            o.put(
				"error",
				error
            );
        }

        return o;
    }

    private static String parseCwd(
		String stdout,
		String fallback
    ) {

        int index =
			stdout.lastIndexOf(
			MARKER_PWD
		);

        if (index < 0) {
            return fallback;
        }

        int start =
			index +
			MARKER_PWD.length();

        int end =
			stdout.indexOf(
			'\n',
			start
		);

        if (end < 0) {
            end = stdout.length();
        }

        String cwd =
			stdout.substring(
			start,
			end
		).trim();

        if (cwd.length() == 0) {
            return fallback;
        }

        return cwd;
    }

    private static String stripMarkers(
		String stdout
    ) {

        int pwdIndex =
			stdout.lastIndexOf(
			MARKER_PWD
		);

        if (pwdIndex < 0) {
            return stdout;
        }

        return stdout.substring(
			0,
			pwdIndex
        );
    }

    private static String getStoredCwd(
		Context context
    ) {

        String value =
			context.getSharedPreferences(
			"terminal_state",
			Context.MODE_PRIVATE
		).getString(
			"cwd",
			TERMUX_HOME
		);

        if (value == null
			|| value.length() == 0) {

            return TERMUX_HOME;
        }

        return value;
    }

    private static void setStoredCwd(
		Context context,
		String cwd
    ) {

        if (cwd == null
			|| cwd.length() == 0) {

            return;
        }

        context.getSharedPreferences(
			"terminal_state",
			Context.MODE_PRIVATE
        )
			.edit()
			.putString(
			"cwd",
			cwd
        )
			.apply();
    }

    private static String shellQuote(
		String value
    ) {

        return "'" +
			value.replace(
			"'",
			"'\\''"
		) +
			"'";
    }

    public static void deliverResult(
		int executionId,
		Bundle result
    ) {

        PendingResult pending;

        synchronized (PENDING_LOCK) {
            pending =
				PENDING.get(
				Integer.valueOf(
					executionId
				)
			);
        }

        if (pending == null) {
            return;
        }

        if (result == null) {

            pending.errCode = -1;

            pending.errMsg =
				"Termux tidak mengirim result bundle";

            pending.exitCode = -1;

            pending.latch.countDown();

            return;
        }

        Bundle nestedResult =
			result.getBundle(
			RESULT_BUNDLE
		);

        if (nestedResult != null) {
            result = nestedResult;
        }

        pending.stdout =
			result.getString(
			RESULT_STDOUT,
			""
		);

        pending.stderr =
			result.getString(
			RESULT_STDERR,
			""
		);

        pending.exitCode =
			result.getInt(
			RESULT_EXIT_CODE,
			-1
		);

        pending.errCode =
			result.getInt(
			RESULT_ERR,
			Activity.RESULT_OK
		);

        pending.errMsg =
			result.getString(
			RESULT_ERRMSG,
			""
		);

        pending.latch.countDown();
    }

    private static final class PendingResult {

        final int executionId;

        final CountDownLatch latch =
		new CountDownLatch(1);

        String stdout;
        String stderr;

        int exitCode = -1;
        int errCode = Activity.RESULT_OK;

        String errMsg;

        PendingResult(
			int executionId
        ) {
            this.executionId =
				executionId;
        }
    }
}
