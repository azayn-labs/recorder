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

## Usage

```
./callrec.sh start      # build, push, (re)start. Run it again after every phone reboot
./callrec.sh status     # running? + recent log
./callrec.sh log        # follow log (shows each source's peak level during a call)
./callrec.sh selftest   # check which capture sources work on this firmware
./callrec.sh pull [dir] # copy recordings to this PC
./callrec.sh stop
```

Building needs the Android SDK (`$ANDROID_HOME`, platform + build-tools) and any JDK 17+.

## Limitations

- **Restart needed after every reboot.** Run `./callrec.sh start` again over USB. Without
  a PC you can use Wireless debugging plus an on-phone adb client (for example Termux's
  `android-tools`) to run the same `app_process` command.
- Any app that switches to communication mode gets recorded (Telegram, Meet, Discord, ...).
  Each one lands in a folder named after its package.
