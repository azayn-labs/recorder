package com.callrec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * User settings written by the companion app to &lt;root&gt;/.config.json:
 * <pre>
 * { "enabled": true, "format": "m4a" | "opus",
 *   "apps": { "Phone": true, "WhatsApp": true, "Instagram": false, "Other": true, ... },
 *   "never": [ { "name": "Mom", "numbers": ["+49 151 2345678"] } ] }
 * </pre>
 * Missing file or keys mean "record everything".
 */
public final class Config {
    private static final int NUMBER_MATCH_DIGITS = 9;

    public boolean enabled = true;
    public AudioWriter.Format format = AudioWriter.Format.M4A;
    private JSONObject apps = new JSONObject();
    private final List<Entry> never = new ArrayList<>();

    private static final class Entry {
        String name;
        final List<String> numbers = new ArrayList<>();
    }

    public static Config load(File root) {
        Config c = new Config();
        File f = new File(root, ".config.json");
        if (!f.exists()) {
            return c;
        }
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            c.enabled = o.optBoolean("enabled", true);
            c.format = AudioWriter.Format.of(o.optString("format"));
            JSONObject apps = o.optJSONObject("apps");
            if (apps != null) {
                c.apps = apps;
            }
            JSONArray never = o.optJSONArray("never");
            for (int i = 0; never != null && i < never.length(); i++) {
                JSONObject e = never.getJSONObject(i);
                Entry entry = new Entry();
                entry.name = e.optString("name", "");
                JSONArray nums = e.optJSONArray("numbers");
                for (int j = 0; nums != null && j < nums.length(); j++) {
                    entry.numbers.add(nums.getString(j));
                }
                c.never.add(entry);
            }
        } catch (Exception e) {
            Log.e("bad config, recording everything", e);
        }
        return c;
    }

    /** {@code label} is a CallInfo.label() value; labels without their own switch use "Other". */
    public boolean appEnabled(String label) {
        String key = apps.has(label) ? label : "Other";
        return apps.optBoolean(key, true);
    }

    /** True if the peer (a display name and/or a phone number) is on the never-record list. */
    public boolean excluded(String name, String number) {
        String nameKey = normalizeName(name);
        String numKey = digits(number);
        if (numKey.isEmpty() && looksLikeNumber(name)) {
            numKey = digits(name);
        }
        for (Entry e : never) {
            if (!nameKey.isEmpty() && nameKey.equals(normalizeName(e.name))) {
                return true;
            }
            for (String n : e.numbers) {
                if (sameNumber(numKey, digits(n))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sameNumber(String a, String b) {
        if (a.length() < 7 || b.length() < 7) {
            return false;
        }
        int n = Math.min(NUMBER_MATCH_DIGITS, Math.min(a.length(), b.length()));
        return a.substring(a.length() - n).equals(b.substring(b.length() - n));
    }

    private static String normalizeName(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    private static boolean looksLikeNumber(String s) {
        return s != null && s.matches("[+0-9 ()\\-.]{7,}");
    }
}
