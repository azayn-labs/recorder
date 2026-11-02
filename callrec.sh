#!/usr/bin/env bash
# Build, install and control the call recorder daemon on an adb-connected phone.
#
#   ./callrec.sh build     compile daemon -> build/callrec.dex
#   ./callrec.sh start     build, push and start the daemon (survives unplugging USB)
#   ./callrec.sh stop      stop the daemon (finalizes an in-progress recording)
#   ./callrec.sh selftest  check which capture sources work on this phone
#   ./callrec.sh selftest simcall   fake a 6 s VoIP call (daemon should save a file)
#   ./callrec.sh app       build and install the companion app (recordings + settings)
#   ./callrec.sh status    is it running? + last log lines
#   ./callrec.sh log       follow the daemon log
#   ./callrec.sh pull [dir]  copy recordings to this computer (default ./recordings)
set -euo pipefail

cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
ADB="adb${ANDROID_SERIAL:+ -s $ANDROID_SERIAL}"
JAVAC="$(command -v javac || ls /opt/android-studio/jbr/bin/javac "$HOME"/.local/share/jdk/*/bin/javac "$HOME"/.gradle/jdks/*/bin/javac 2>/dev/null | head -1)"
APP_ID=com.callrec.app

DEX_REMOTE=/data/local/tmp/callrec.dex
LOG_REMOTE=/data/local/tmp/callrec.log
PID_REMOTE=/data/local/tmp/callrec.pid
OUT_REMOTE=/sdcard/CallRecorder

build() {
    rm -rf build && mkdir -p build/classes
    "$JAVAC" -nowarn -source 17 -target 17 -Xlint:-options \
        -cp "$PLATFORM/android.jar" -d build/classes \
        $(find daemon/src -name '*.java')
    "$BUILD_TOOLS/d8" --min-api 31 --lib "$PLATFORM/android.jar" \
        --output build $(find build/classes -name '*.class')
    mv build/classes.dex build/callrec.dex
    echo "built build/callrec.dex"
}

running_pid() {
    $ADB shell "p=\$(cat $PID_REMOTE 2>/dev/null); [ -n \"\$p\" ] && grep -q com.callrec /proc/\$p/cmdline 2>/dev/null && echo \$p; true" | tr -d '\r'
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
    build
    stop >/dev/null
    $ADB push build/callrec.dex "$DEX_REMOTE" >/dev/null
    $ADB shell "mkdir -p $OUT_REMOTE"
    # Detached from adb (reparented to init), so it keeps running after USB is unplugged.
    # adb itself can hang waiting on the session, so don't wait for it.
    timeout 5 $ADB shell -T "CLASSPATH=$DEX_REMOTE nohup setsid app_process / com.callrec.Main $OUT_REMOTE >$LOG_REMOTE 2>&1 </dev/null &" || true
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
    # Gradle needs a full JDK; reuse the one javac came from.
    (cd android && JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVAC")")")" ./gradlew -q assembleDebug)
    $ADB install -r android/app/build/outputs/apk/debug/app-debug.apk
    $ADB shell appops set "$APP_ID" MANAGE_EXTERNAL_STORAGE allow
    $ADB shell pm grant "$APP_ID" android.permission.READ_CONTACTS
    $ADB shell am start -n "$APP_ID/.MainActivity" >/dev/null
}

case "${1:-}" in
    build) build ;;
    start) start ;;
    app) app ;;
    stop) stop ;;
    status) status ;;
    selftest)
        build
        $ADB push build/callrec.dex /data/local/tmp/callrec-test.dex >/dev/null
        $ADB shell "CLASSPATH=/data/local/tmp/callrec-test.dex app_process / com.callrec.SelfTest ${2:-}" | grep -v '^\s*at '
        ;;
    log) $ADB shell "tail -n 50 -f $LOG_REMOTE" ;;
    pull)
        dest="${2:-recordings}"
        mkdir -p "$dest"
        $ADB pull "$OUT_REMOTE/." "$dest"
        ;;
    *) sed -n '2,13p' "$0"; exit 1 ;;
esac
