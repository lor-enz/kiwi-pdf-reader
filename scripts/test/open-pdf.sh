#!/bin/bash
# Opens a local PDF in the app the way another app would: pushed to Download, then a VIEW
# intent with a media store URI. Each opens in a card of its own, like from a file manager.
#
# Usage: scripts/test/open-pdf.sh [--fresh] [--no-grant] <file>
#   --fresh     stop the app and clear its data first; otherwise a file opened before reopens at
#               its last page, and one that's still open switches to its card
#   --no-grant  leave out the read permission, like a sender that forgets to grant it
# Pick the device with ANDROID_SERIAL (e.g. ANDROID_SERIAL=emulator-5554).
set -euo pipefail

grant=--grant-read-uri-permission
fresh=
while [ $# -gt 1 ]; do
    case "$1" in
        --fresh) fresh=1 ;;
        --no-grant) grant= ;;
        *) break ;;
    esac
    shift
done
[ $# -eq 1 ] || { sed -n 's/^# \{0,1\}//; 2,10p' "$0"; exit 1; }

name=$(basename "$1")
path=/sdcard/Download/$name
adb push "$1" "$path" >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$path" >/dev/null
id=
for _ in 1 2 3 4 5; do
    id=$(adb shell content query --uri content://media/external/file --projection _id \
        --where "\"_data='/storage/emulated/0/Download/$name'\"" | grep -o '_id=[0-9]*' | head -1 | cut -d= -f2)
    [ -n "$id" ] && break
    sleep 1
done
[ -n "$id" ] || { echo "Media scanner didn't index $path" >&2; exit 1; }

if [ -n "$fresh" ]; then adb shell pm clear kiwi.lorenz.pdfreader >/dev/null; fi
adb shell am start -a android.intent.action.VIEW -t application/pdf \
    -d "content://media/external/file/$id" $grant -n kiwi.lorenz.pdfreader/.ExternalViewerActivity >/dev/null
echo "Opened $name (content://media/external/file/$id)"
