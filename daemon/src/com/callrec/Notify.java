package com.callrec;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tells the companion app (com.callrec.app/.CallNotifier) about the recording state so it can show
 * the ongoing-recording notification. Uses an explicit broadcast from the shell uid; the receiver
 * requires android.permission.DUMP, which shell has and normal apps can't get.
 * Runs `am` off the polling thread since it takes a few hundred ms.
 */
public final class Notify {
    private static final String RECEIVER = "com.callrec.app/.CallNotifier";
    private static final String ACTION = "com.callrec.app.CALL_STATE";
    // FLAG_RECEIVER_FOREGROUND | FLAG_INCLUDE_STOPPED_PACKAGES
    private static final String FLAGS = String.valueOf(0x10000000 | 0x20);

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private Notify() {
    }

    public static void recording(String app, String peer, long since) {
        send("recording", app, peer, since);
    }

    public static void idle() {
        send("idle", null, null, 0);
    }

    private static void send(String state, String app, String peer, long since) {
        EXECUTOR.execute(() -> {
            String out = CallInfo.exec("am", "broadcast", "-n", RECEIVER, "-a", ACTION, "-f", FLAGS,
                    "--es", "state", state,
                    "--es", "app", app != null ? app : "",
                    "--es", "peer", peer != null ? peer : "",
                    "--el", "since", String.valueOf(since));
            if (out == null || !out.contains("result=")) {
                Log.d("notify " + state + ": " + out);
            }
        });
    }

    /** Waits for queued broadcasts (used on shutdown so the notification is cleared). */
    public static void flush() {
        EXECUTOR.shutdown();
        try {
            EXECUTOR.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }
}
