package com.callrec;

import android.media.AudioManager;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Call recorder daemon. Launched via adb as uid shell:
 *   CLASSPATH=/data/local/tmp/callrec.dex app_process / com.callrec.Main [outputDir]
 * Polls the audio mode and records every cellular (MODE_IN_CALL) and VoIP
 * (MODE_IN_COMMUNICATION) call to outputDir/&lt;App&gt;/&lt;date&gt;_&lt;peer&gt;.m4a.
 */
public final class Main {
    private static final long POLL_MS = 500;
    private static final int START_POLLS = 2; // call mode must hold for 1 s before recording
    private static final int STOP_POLLS = 4; // and be gone for 2 s before stopping
    private static final long MIN_DURATION_MS = 2_000;
    private static final long PEER_REFRESH_MS = 5_000;

    private static File root;
    private static Recording current;
    private static String currentPkg;
    private static String currentPeer;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        root = new File(args.length > 0 ? args[0] : "/sdcard/CallRecorder");
        Env.init();
        writePid();
        Log.i("callrec started, pid " + android.os.Process.myPid() + ", saving to " + root);

        Runtime.getRuntime().addShutdownHook(new Thread(Main::finish));

        AudioManager am = CallInfo.audioManager();
        Recording.Kind pending = null;
        int pendingPolls = 0;
        int idlePolls = 0;
        long nextPeerCheck = 0;

        while (true) {
            int mode = am.getMode();
            Recording.Kind kind = kindFor(mode);

            if (current == null) {
                if (kind != null && kind == pending) {
                    if (++pendingPolls >= START_POLLS) {
                        begin(am, kind);
                        nextPeerCheck = 0;
                        idlePolls = 0;
                        pending = null;
                    }
                } else {
                    pending = kind;
                    pendingPolls = kind != null ? 1 : 0;
                }
            } else if (kind == current.kind) {
                idlePolls = 0;
                long now = System.currentTimeMillis();
                if (current.kind == Recording.Kind.VOIP && now >= nextPeerCheck) {
                    refreshVoipInfo(am);
                    nextPeerCheck = now + PEER_REFRESH_MS;
                }
            } else if (++idlePolls >= STOP_POLLS) {
                finish();
            }

            Thread.sleep(POLL_MS);
        }
    }

    private static Recording.Kind kindFor(int mode) {
        if (mode == AudioManager.MODE_IN_CALL) {
            return Recording.Kind.CELLULAR;
        }
        if (mode == AudioManager.MODE_IN_COMMUNICATION) {
            return Recording.Kind.VOIP;
        }
        return null;
    }

    private static void begin(AudioManager am, Recording.Kind kind) {
        File tmpDir = new File(root, ".tmp");
        tmpDir.mkdirs();
        File tmp = new File(tmpDir, System.currentTimeMillis() + ".m4a");
        currentPkg = kind == Recording.Kind.VOIP ? CallInfo.voipPackage(am) : null;
        currentPeer = null;
        Log.i("call started: " + kind + (currentPkg != null ? " (" + currentPkg + ")" : ""));
        current = new Recording(kind, tmp);
        current.start();
    }

    private static void refreshVoipInfo(AudioManager am) {
        if (currentPkg == null) {
            currentPkg = CallInfo.voipPackage(am);
        }
        String peer = CallInfo.voipPeer(currentPkg);
        if (peer != null && !peer.equals(currentPeer)) {
            currentPeer = peer;
            Log.i("peer: " + peer);
        }
    }

    private static synchronized void finish() {
        Recording rec = current;
        if (rec == null) {
            return;
        }
        current = null;
        long duration = rec.stop();
        Log.i("call ended: " + rec.kind + ", " + duration / 1000 + " s");
        if (duration < MIN_DURATION_MS) {
            rec.file.delete();
            return;
        }

        String app;
        String peer;
        if (rec.kind == Recording.Kind.CELLULAR) {
            app = "Phone";
            peer = CallInfo.cellularPeer(rec.startedAt);
        } else {
            app = CallInfo.label(currentPkg);
            peer = currentPeer;
        }

        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date(rec.startedAt));
        String name = stamp + (peer != null ? "_" + sanitize(peer) : "") + ".m4a";
        File dir = new File(root, app);
        dir.mkdirs();
        File dest = new File(dir, name);
        if (rec.file.renameTo(dest)) {
            Log.i("saved " + dest);
        } else {
            Log.i("rename failed, kept " + rec.file);
        }
    }

    private static String sanitize(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim().replace(' ', '_');
        return r.length() > 60 ? r.substring(0, 60) : r;
    }

    private static void writePid() {
        try (FileWriter w = new FileWriter("/data/local/tmp/callrec.pid")) {
            w.write(String.valueOf(android.os.Process.myPid()));
        } catch (Exception e) {
            Log.e("could not write pid file", e);
        }
    }
}
