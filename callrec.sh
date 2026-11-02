#!/usr/bin/env bash
# Build, install and control the call recorder daemon on an adb-connected phone.
#
#   ./callrec.sh build     compile daemon -> build/callrec.dex
#   ./callrec.sh start     build, push and start the daemon (survives unplugging USB)
#   ./callrec.sh stop      stop the daemon (finalizes an in-progress recording)
#   ./callrec.sh selftest  check which capture sources work on this phone
#   ./callrec.sh selftest simcall [s]  fake a VoIP call, default 6 s (daemon should save a file)
#   ./callrec.sh app       build and install the companion app (recordings + settings)
#   ./callrec.sh status    is it running? + last log lines
#   ./callrec.sh log       follow the daemon log
#   ./callrec.sh pull [dir]  copy recordings to this computer (default ./recordings)
#
# In a source checkout, start/app/selftest build first (Android SDK + JDK 17+ needed).
# From a GitHub release download (callrec.sh, callrec.dex and AzaynRecorder-*.apk in one
# folder) they use those files as they are; only adb is needed.
set -euo pipefail

cd "$(dirname "$0")"
ADB="adb${ANDROID_SERIAL:+ -s $ANDROID_SERIAL}"
APP_ID=com.azayn.recorder
FROM_SOURCE=$([ -d daemon/src ] && echo 1 || echo "")

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
JAVAC="$(command -v javac || ls /opt/android-studio/jbr/bin/javac "$HOME"/.local/share/jdk/*/bin/javac "$HOME"/.gradle/jdks/*/bin/javac 2>/dev/null | head -1 || true)"

DEX_REMOTE=/data/local/tmp/callrec.dex
LOG_REMOTE=/data/local/tmp/callrec.log
PID_REMOTE=/data/local/tmp/callrec.pid
OUT_REMOTE=/sdcard/CallRecorder

build() {
    local PLATFORM BUILD_TOOLS
    PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
    BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
    rm -rf build && mkdir -p build/classes
    "$JAVAC" -nowarn -source 17 -target 17 -Xlint:-options \
        -cp "$PLATFORM/android.jar" -d build/classes \
        $(find daemon/src -name '*.java')
    "$BUILD_TOOLS/d8" --min-api 31 --lib "$PLATFORM/android.jar" \
        --output build $(find build/classes -name '*.class')
    mv build/classes.dex build/callrec.dex
    echo "built build/callrec.dex"
}

# The daemon to push: freshly built from source, or the prebuilt one from a release.
daemon_dex() {
    if [ -n "$FROM_SOURCE" ]; then
        build >&2
        echo build/callrec.dex
    elif [ -f callrec.dex ]; then
        echo callrec.dex
    else
        echo "callrec.dex not found: download it from the GitHub release next to this script" >&2
        exit 1
    fi
}

running_pid() {
    $ADB shell "p=\$(cat $PID_REMOTE 2>/dev/null); [ -n \"\$p\" ] && grep -q com.azayn.recorder.daemon /proc/\$p/cmdline 2>/dev/null && echo \$p; true" | tr -d '\r'
}

stop() {
    local pid
    pid="$(running_pid)"
    if [ -n "$pid" ]; then
        $ADB shell "kill $pid"
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            [ -z "$(running_pid)" ] && break
            sleep 1
        done
        echo "stopped (pid $pid)"
    else
        echo "not running"
    fi
}

start() {
    local dex
    dex="$(daemon_dex)"
    stop >/dev/null
    $ADB push "$dex" "$DEX_REMOTE" >/dev/null
    $ADB shell "mkdir -p $OUT_REMOTE"
    # Detached from adb (reparented to init), so it keeps running after USB is unplugged.
    # adb itself can hang waiting on the session, so don't wait for it.
    timeout 5 $ADB shell -T "CLASSPATH=$DEX_REMOTE nohup setsid app_process / com.azayn.recorder.daemon.Main $OUT_REMOTE >$LOG_REMOTE 2>&1 </dev/null &" || true
    sleep 2
    status
}

status() {
    local pid
    pid="$(running_pid)"
    if [ -n "$pid" ]; then echo "running (pid $pid)"; else echo "NOT running"; fi
    $ADB shell "tail -n 15 $LOG_REMOTE 2>/dev/null" || true
}

app() {
    local apk
    if [ -n "$FROM_SOURCE" ]; then
        # Gradle needs a full JDK; reuse the one javac came from.
        (cd android && JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVAC")")")" ./gradlew -q assembleDebug)
        apk=android/app/build/outputs/apk/debug/app-debug.apk
    else
        apk="$(ls AzaynRecorder-*.apk 2>/dev/null | sort -V | tail -1)"
        [ -n "$apk" ] || { echo "AzaynRecorder-*.apk not found: download it from the GitHub release" >&2; exit 1; }
    fi
    $ADB install -r "$apk"
    $ADB shell appops set "$APP_ID" MANAGE_EXTERNAL_STORAGE allow
    for p in READ_CONTACTS RECORD_AUDIO POST_NOTIFICATIONS; do
        $ADB shell pm grant "$APP_ID" android.permission.$p
    done
    $ADB shell am start -n "$APP_ID/.MainActivity" >/dev/null
}

case "${1:-}" in
    build) build ;;
    start) start ;;
    app) app ;;
    stop) stop ;;
    status) status ;;
    selftest)
        dex="$(daemon_dex)"
        $ADB push "$dex" /data/local/tmp/callrec-test.dex >/dev/null
        $ADB shell "CLASSPATH=/data/local/tmp/callrec-test.dex app_process / com.azayn.recorder.daemon.SelfTest ${2:-} ${3:-}" | grep -v '^\s*at '
        ;;
    log) $ADB shell "tail -n 50 -f $LOG_REMOTE" ;;
    pull)
        dest="${2:-recordings}"
        mkdir -p "$dest"
        $ADB pull "$OUT_REMOTE/." "$dest"
        ;;
    *) sed -n '2,17p' "$0"; exit 1 ;;
esac
