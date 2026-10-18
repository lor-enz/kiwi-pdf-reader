# Sourced by the other scripts: picks the device. ANDROID_SERIAL wins; otherwise the one running
# emulator, so a phone that's connected too is never used by accident.

if [ -z "${ANDROID_SERIAL:-}" ]; then
    emulators=$(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }')
    case $(printf '%s' "$emulators" | grep -c . || true) in
        1) export ANDROID_SERIAL=$emulators ;;
        0) echo "No emulator running. Start one, or set ANDROID_SERIAL to use a phone." >&2; exit 1 ;;
        *) echo "Several emulators running ($(echo $emulators)); pick one with ANDROID_SERIAL." >&2; exit 1 ;;
    esac
fi

PACKAGE=kiwi.lorenz.pdfreader
