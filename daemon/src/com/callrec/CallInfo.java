package com.callrec;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Figures out which app owns the current call and who is on the other end. */
public final class CallInfo {
    private static final Map<String, String> LABELS = new HashMap<>();

    static {
        LABELS.put("com.whatsapp", "WhatsApp");
        LABELS.put("com.whatsapp.w4b", "WhatsApp Business");
        LABELS.put("com.instagram.android", "Instagram");
        LABELS.put("com.facebook.katana", "Facebook");
        LABELS.put("com.facebook.orca", "Messenger");
        LABELS.put("com.facebook.mlite", "Messenger");
    }

    private static AudioManager audioManager;

    private CallInfo() {
    }

    public static String label(String pkg) {
        if (pkg == null) {
            return "Other";
        }
        String l = LABELS.get(pkg);
        return l != null ? l : pkg;
    }

    /** Package of the app doing VoIP right now, or null if it can't be determined. */
    public static String voipPackage(AudioManager am) {
        try {
            for (AudioPlaybackConfiguration c : am.getActivePlaybackConfigurations()) {
                if (c.getAudioAttributes().getUsage() == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
                    String pkg = packageForUid((int) AudioPlaybackConfiguration.class.getMethod("getClientUid").invoke(c));
                    if (pkg != null) {
                        return pkg;
                    }
                }
            }
        } catch (Throwable t) {
            Log.d("playback configs: " + t);
        }
        try {
            for (AudioRecordingConfiguration c : am.getActiveRecordingConfigurations()) {
                if (c.getClientAudioSource() == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                    String pkg = packageForUid((int) AudioRecordingConfiguration.class.getMethod("getClientUid").invoke(c));
                    if (pkg != null) {
                        return pkg;
                    }
                }
            }
        } catch (Throwable t) {
            Log.d("recording configs: " + t);
        }
        return null;
    }

    /** First package of an app uid; null for system/shell uids (which can't be a VoIP app). */
    private static String packageForUid(int uid) {
        if (uid % 100_000 < 10_000) {
            return null;
        }
        String[] pkgs = Env.context().getPackageManager().getPackagesForUid(uid);
        return pkgs != null && pkgs.length > 0 ? pkgs[0] : null;
    }

    /** An AudioManager bound to our shell context (the system one has no app context). */
    public static synchronized AudioManager audioManager() {
        if (audioManager == null) {
            try {
                Constructor<AudioManager> c = AudioManager.class.getDeclaredConstructor(Context.class);
                c.setAccessible(true);
                audioManager = c.newInstance(Env.context());
            } catch (Throwable t) {
                Log.e("AudioManager(Context) failed, using system service", t);
                audioManager = (AudioManager) Env.context().getSystemService(Context.AUDIO_SERVICE);
            }
        }
        return audioManager;
    }

    private static final Pattern RECORD = Pattern.compile("NotificationRecord\\(.*?pkg=(\\S+)");
    private static final Pattern TITLE = Pattern.compile("android\\.title=\\S+ \\((.*)\\)\\s*$");

    /**
     * Best-effort name of the person on a VoIP call, taken from the app's ongoing call
     * notification title. Returns null if not found.
     */
    public static String voipPeer(String pkg) {
        if (pkg == null) {
            return null;
        }
        String out = exec("dumpsys", "notification", "--noredact");
        if (out == null) {
            return null;
        }
        String fallback = null;
        boolean inPkg = false;
        boolean isCall = false;
        for (String line : out.split("\n")) {
            Matcher m = RECORD.matcher(line);
            if (m.find()) {
                inPkg = pkg.equals(m.group(1));
                isCall = false;
                continue;
            }
            if (!inPkg) {
                continue;
            }
            if (line.contains("category=call") || line.contains("CallStyle")) {
                isCall = true;
            }
            Matcher t = TITLE.matcher(line.trim());
            if (t.find()) {
                String title = t.group(1).trim();
                if (isCall) {
                    return title;
                }
                if (fallback == null) {
                    fallback = title;
                }
            }
        }
        return fallback;
    }

    /** The other party of a call. Either field may be null. */
    public static final class Peer {
        public final String name;
        public final String number;

        Peer(String name, String number) {
            this.name = name;
            this.number = number;
        }

        /** "Name (number)", "Name" or "number"; null if nothing is known. */
        public String display() {
            if (name != null && number != null) {
                return name + " (" + number + ")";
            }
            return name != null ? name : number;
        }
    }

    /** Number of a ringing/active incoming cellular call, or null (outgoing calls don't expose it). */
    public static String incomingNumber() {
        String out = exec("dumpsys", "telephony.registry");
        if (out == null) {
            return null;
        }
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("mCallIncomingNumber=")) {
                String n = t.substring("mCallIncomingNumber=".length()).trim();
                if (!n.isEmpty()) {
                    return n;
                }
            }
        }
        return null;
    }

    /** Name/number of the most recent cellular call started at or after {@code sinceMs}. */
    public static Peer cellularPeer(long sinceMs) {
        for (int attempt = 0; attempt < 6; attempt++) {
            String out = exec("content", "query", "--uri", "content://call_log/calls",
                    "--projection", "number:name:date",
                    "--where", "date>=" + (sinceMs - 120_000));
            if (out != null) {
                Peer best = null;
                long bestDate = -1;
                for (String line : out.split("\n")) {
                    String date = field(line, "date");
                    if (date == null) {
                        continue;
                    }
                    long d;
                    try {
                        d = Long.parseLong(date);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (d > bestDate) {
                        bestDate = d;
                        best = new Peer(nullIfEmpty(field(line, "name")), nullIfEmpty(field(line, "number")));
                    }
                }
                if (best != null) {
                    return best;
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }
        return new Peer(null, null);
    }

    private static String nullIfEmpty(String s) {
        return s == null || s.isEmpty() || s.equals("NULL") ? null : s;
    }

    private static String field(String row, String key) {
        Matcher m = Pattern.compile("(?:^|, |Row: \\d+ )" + key + "=(.*?)(?=, \\w+=|$)").matcher(row);
        return m.find() ? m.group(1) : null;
    }

    static String exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            Log.d("exec " + cmd[0] + ": " + t);
            return null;
        }
    }
}
