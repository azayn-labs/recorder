# call-recorder

Automatic call recorder for a Samsung Galaxy Z Fold 7 (Android 17 / One UI), set up over adb.
It records:

- regular phone calls (SIM / cellular)
- WhatsApp, Instagram and Facebook/Messenger calls (and any other VoIP app)

## How it works

Normal Android apps can't capture call audio. The `shell` user that adb runs as can,
because it holds `CAPTURE_AUDIO_OUTPUT` and `CAPTURE_VOICE_COMMUNICATION_OUTPUT`.
So the recorder is a small Java daemon (`daemon/src`), compiled to a dex file and started
with `app_process` as the shell user (the same technique scrcpy uses). It detaches from
adb, so it keeps running after you unplug USB, until the phone reboots.

The daemon checks the audio mode every 500 ms:

| Mode | Call type | Captured from |
|---|---|---|
| `MODE_IN_CALL` | cellular | `VOICE_CALL` source (both sides, straight from the modem). Falls back to uplink + downlink, then the mic. |
| `MODE_IN_COMMUNICATION` | VoIP | mic (your voice) + a loopback audio policy on `USAGE_VOICE_COMMUNICATION` (their voice; it still plays on the phone) |

The two sides are mixed to mono and encoded as 64 kbps AAC.

## Files

```
/sdcard/CallRecorder/
  Phone/      2026-11-02_14-03-11_John_+4915123456789.m4a
  WhatsApp/   2026-11-02_15-20-45_Jane.m4a
  Instagram/
  Facebook/   (or Messenger/)
```

Names come from the call log for cellular calls, and from the app's ongoing-call
notification title for VoIP calls. They're best effort. Recordings under 2 s are dropped.
You can browse them in *My Files › Internal storage › CallRecorder*.

## Companion app (`android/`)

The **Call Recorder** app on the phone (`./callrec.sh app` builds, installs and grants its permissions):

- **Recordings tab:** recordings grouped by day, filter by app, search by name or number,
  built-in player (seek, ±10 s), share, delete, and "Never record this person" from a row's menu.
  A banner shows when the recorder is not running or is recording right now.
- **Settings tab:** recorder status, a master "Record calls" switch, per-app switches
  (Phone, WhatsApp, Instagram, Facebook, Messenger, other apps), and the **Never record**
  list. Add people from your contacts (all their numbers) or by name/username.

The app and the daemon talk through two files in `/sdcard/CallRecorder`:

- `.config.json`: written by the app, read by the daemon at the start of each call.
- `.status.json`: the daemon's heartbeat, every 2 s.

How the never-record list is enforced:

| Call | Matched by | When |
|---|---|---|
| Incoming phone call | number | before recording starts, so it's never recorded |
| Outgoing phone call | number/name from the call log | after hang-up; the file is deleted right away (Android doesn't expose the dialed number during the call) |
| WhatsApp / Instagram / Facebook | name or username shown in the app's call notification | a few seconds into the call; the partial recording is discarded |

## Usage

```
./callrec.sh start      # build, push, (re)start. Run it again after every phone reboot
./callrec.sh app        # build + install the companion app
./callrec.sh status     # running? + recent log
./callrec.sh log        # follow log (shows each source's peak level during a call)
./callrec.sh selftest   # check which capture sources work on this firmware
./callrec.sh pull [dir] # copy recordings to this PC
./callrec.sh stop
```

Building needs the Android SDK (`$ANDROID_HOME`, platform + build-tools) and a JDK 17+
(Android Studio's bundled one is found automatically).

## Limitations

- **Restart needed after every reboot.** Run `./callrec.sh start` again over USB. Without
  a PC you can use Wireless debugging plus an on-phone adb client (for example Termux's
  `android-tools`) to run the same `app_process` command.
- Any app that switches to communication mode gets recorded (Telegram, Meet, Discord, ...).
  Each one lands in a folder named after its package.
