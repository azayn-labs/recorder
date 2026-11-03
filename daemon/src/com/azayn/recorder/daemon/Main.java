package com.azayn.recorder.daemon;

import android.media.AudioManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Call recorder daemon. Launched via adb as uid shell:
 *   CLASSPATH=/data/local/tmp/callrec.dex app_process / com.azayn.recorder.daemon.Main [outputDir]
 * Polls the audio mode and records every cellular (MODE_IN_CALL) and VoIP
 * (MODE_IN_COMMUNICATION) call to outputDir/&lt;App&gt;/&lt;contact&gt;/&lt;date&gt;.&lt;m4a|opus&gt;,
 * honoring the companion app's .config.json and reporting to .status.json.
 */
public final class Main {
    public static final int VERSION = 6;

    private static final long POLL_MS = 500;
    private static final int START_POLLS = 2; // call mode must hold for 1 s before recording
    private static final int STOP_POLLS = 4; // and be gone for 2 s before stopping
    private static final long MIN_DURATION_MS = 2_000;
    private static final long PEER_REFRESH_MS = 3_000;
    private static final long STATUS_MS = 2_000;

    private static File root;
    private static Config config;
    private static Recording current;
    private static String currentPkg;
    private static String currentPeer;
    private static String currentNumber;
    /** Kind of a call we decided not to record; set until that call ends. */
    private static Recording.Kind skipping;
    private static String skipReason;
    /** The user hit "Stop" on the notification: capture has ended but the call hasn't. */
    private static boolean stoppedEarly;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        root = new File(args.length > 0 ? args[0] : "/sdcard/CallRecorder");
        Env.init();
        writePid();
        Log.i("recorder daemon v" + VERSION + " started, pid " + android.os.Process.myPid() + ", saving to " + root);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            finish();
            Notify.flush();
        }));
        Notify.idle(); // clear a notification left over from a previous run

        AudioManager am = CallInfo.audioManager();
        Recording.Kind pending = null;
        int pendingPolls = 0;
        int idlePolls = 0;
        long nextPeerCheck = 0;
        long nextStatus = 0;

        while (true) {
            Recording.Kind kind = kindFor(am.getMode());

            if (skipping != null) {
                if (kind == skipping) {
                    idlePolls = 0;
                } else if (++idlePolls >= STOP_POLLS) {
                    Log.i("skipped call ended");
                    skipping = null;
                    skipReason = null;
                }
            } else if (current == null) {
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
                handleControl();
                long now = System.currentTimeMillis();
                if (current != null && current.kind == Recording.Kind.VOIP && now >= nextPeerCheck) {
                    refreshVoipInfo(am);
                    nextPeerCheck = now + PEER_REFRESH_MS;
                }
            } else if (++idlePolls >= STOP_POLLS) {
                finish();
            }

            long now = System.currentTimeMillis();
            if (now >= nextStatus) {
                writeStatus();
                nextStatus = now + STATUS_MS;
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
        config = Config.load(root);
        currentPkg = kind == Recording.Kind.VOIP ? CallInfo.voipPackage(am) : null;
        currentPeer = null;
        currentNumber = kind == Recording.Kind.CELLULAR ? CallInfo.incomingNumber() : null;
        String app = appLabel(kind);
        Log.i("call started: " + kind + " " + app + (currentNumber != null ? " " + currentNumber : ""));

        if (!config.enabled) {
            skip(kind, "recording is paused");
            return;
        }
        if ((kind == Recording.Kind.CELLULAR || currentPkg != null) && !config.appEnabled(app)) {
            skip(kind, app + " recording is off");
            return;
        }
        if (config.excluded(null, currentNumber)) {
            skip(kind, "number is on the never-record list");
            return;
        }

        File tmpDir = new File(root, ".tmp");
        tmpDir.mkdirs();
        new File(root, ".control").delete(); // ignore commands meant for an earlier call
        stoppedEarly = false;
        current = new Recording(kind, new File(tmpDir, System.currentTimeMillis() + "." + config.format.extension), config.format);
        current.start();
        notifyRecording();
        writeStatus();
    }

    /** Commands from the notification's buttons, written by the app to &lt;root&gt;/.control. */
    private static void handleControl() {
        File f = new File(root, ".control");
        if (stoppedEarly || !f.exists()) {
            return;
        }
        String cmd;
        try {
            cmd = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            cmd = "";
        }
        f.delete();
        if (cmd.equals("discard")) {
            discard("discarded from the notification");
        } else if (cmd.equals("stop")) {
            // Finalize the audio now; the file is named and saved when the call ends.
            current.stop();
            stoppedEarly = true;
            Log.i("stopped from the notification");
            Notify.idle();
            writeStatus();
        }
    }

    private static void notifyRecording() {
        Recording rec = current;
        if (rec != null && !stoppedEarly) {
            Notify.recording(appLabel(rec.kind), currentPeer != null ? currentPeer : currentNumber, rec.startedAt);
        }
    }

    private static void skip(Recording.Kind kind, String reason) {
        Log.i("not recording: " + reason);
        skipping = kind;
        skipReason = reason;
        writeStatus();
    }

    private static String appLabel(Recording.Kind kind) {
        return kind == Recording.Kind.CELLULAR ? "Phone" : CallInfo.label(currentPkg);
    }

    private static void refreshVoipInfo(AudioManager am) {
        if (currentPkg == null) {
            currentPkg = CallInfo.voipPackage(am);
            if (currentPkg != null) {
                if (!config.appEnabled(appLabel(Recording.Kind.VOIP))) {
                    discard(appLabel(Recording.Kind.VOIP) + " recording is off");
                    return;
                }
                notifyRecording();
            }
        }
        String peer = CallInfo.voipPeer(currentPkg);
        if (peer != null && !peer.equals(currentPeer)) {
            currentPeer = peer;
            Log.i("peer: " + peer);
            if (config.excluded(peer, null)) {
                discard("contact is on the never-record list");
                return;
            }
            notifyRecording();
            writeStatus();
        }
    }

    /** Stops the current recording and deletes it, ignoring the rest of the call. */
    private static synchronized void discard(String reason) {
        Recording rec = current;
        if (rec == null) {
            return;
        }
        current = null;
        rec.stop();
        rec.file.delete();
        Notify.idle();
        Log.i("discarded recording");
        skip(rec.kind, reason);
    }

    private static synchronized void finish() {
        Recording rec = current;
        if (rec == null) {
            return;
        }
        current = null;
        stoppedEarly = false;
        Notify.idle();
        long duration = rec.stop();
        Log.i("call ended: " + rec.kind + ", " + duration / 1000 + " s");
        writeStatus();
        if (duration < MIN_DURATION_MS) {
            rec.file.delete();
            return;
        }

        String app = appLabel(rec.kind);
        String peer;
        if (rec.kind == Recording.Kind.CELLULAR) {
            CallInfo.Peer p = CallInfo.cellularPeer(rec.startedAt);
            if (config.excluded(p.name, p.number != null ? p.number : currentNumber)) {
                rec.file.delete();
                Log.i("deleted: contact is on the never-record list");
                return;
            }
            peer = p.display() != null ? p.display() : currentNumber;
        } else {
            // Unknown apps were recorded provisionally; now the "Other" switch applies.
            if (!config.appEnabled(app)) {
                rec.file.delete();
                Log.i("deleted: " + app + " recording is off");
                return;
            }
            peer = currentPeer;
        }

        // <root>/<App>/<contact or number>/YYYY-MM-DD-HH-MM-SS.<m4a|opus>
        String stamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new Date(rec.startedAt));
        String contact = peer != null ? sanitize(peer) : "";
        File dir = new File(new File(root, app), contact.isEmpty() ? "Unknown" : contact);
        dir.mkdirs();
        String ext = rec.file.getName().substring(rec.file.getName().lastIndexOf('.'));
        File dest = new File(dir, stamp + ext);
        for (int i = 2; dest.exists(); i++) {
            dest = new File(dir, stamp + "-" + i + ext);
        }
        if (rec.file.renameTo(dest)) {
            Log.i("saved " + dest);
        } else {
            Log.i("rename failed, kept " + rec.file);
        }
    }

    private static String sanitize(String s) {
        // Also strip leading dots so a name can't produce a hidden or "." / ".." folder.
        String r = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").replaceAll("\\s+", " ").trim().replaceAll("^\\.+", "");
        return r.length() > 80 ? r.substring(0, 80) : r;
    }

    /** Heartbeat for the companion app: .status.json, rewritten atomically every few seconds. */
    private static synchronized void writeStatus() {
        try {
            JSONObject o = new JSONObject();
            o.put("version", VERSION);
            o.put("pid", android.os.Process.myPid());
            o.put("heartbeat", System.currentTimeMillis());
            Recording rec = current;
            if (rec != null) {
                o.put("state", stoppedEarly ? "stopped" : "recording");
                o.put("app", appLabel(rec.kind));
                o.put("since", rec.startedAt);
                String peer = currentPeer != null ? currentPeer : currentNumber;
                if (peer != null) {
                    o.put("peer", peer);
                }
            } else if (skipping != null) {
                o.put("state", "skipping");
                o.put("reason", skipReason);
            } else {
                o.put("state", "idle");
            }
            root.mkdirs();
            File tmp = new File(root, ".status.json.tmp");
            Files.write(tmp.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            tmp.renameTo(new File(root, ".status.json"));
        } catch (Exception e) {
            Log.e("could not write status", e);
        }
    }

    private static void writePid() {
        try (FileWriter w = new FileWriter("/data/local/tmp/callrec.pid")) {
            w.write(String.valueOf(android.os.Process.myPid()));
        } catch (Exception e) {
            Log.e("could not write pid file", e);
        }
    }
}
