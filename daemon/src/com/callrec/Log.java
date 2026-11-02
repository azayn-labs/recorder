package com.callrec;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Logs to stdout (redirected to a file by the launcher) and logcat. */
public final class Log {
    private static final String TAG = "CallRec";
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private Log() {
    }

    public static synchronized void i(String msg) {
        System.out.println(FMT.format(new Date()) + " I " + msg);
        android.util.Log.i(TAG, msg);
    }

    public static synchronized void d(String msg) {
        System.out.println(FMT.format(new Date()) + " D " + msg);
        android.util.Log.d(TAG, msg);
    }

    public static synchronized void e(String msg, Throwable t) {
        System.out.println(FMT.format(new Date()) + " E " + msg + (t != null ? ": " + t : ""));
        if (t != null) {
            t.printStackTrace(System.out);
        }
        android.util.Log.e(TAG, msg, t);
    }
}
