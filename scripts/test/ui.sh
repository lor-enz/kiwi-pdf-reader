#!/bin/bash
# Reads and drives the screen through uiautomator. Elements are found by test tag (resource id,
# see TestTags.kt), text or content description.
#
# Usage: scripts/test/ui.sh <command>
#   list               visible elements with tag, text and center
#   tap <target>       taps the first element whose tag, text or description is <target>
#   hold <target>      long-presses it
#   text <target>      prints the text of that element
#   wait <target> [s]  waits until it's shown (default 10 s)
#   shot <file.png>    saves a screenshot
# Pick the device with ANDROID_SERIAL (e.g. ANDROID_SERIAL=emulator-5554).
set -euo pipefail

usage() { sed -n 's/^# \{0,1\}//; 2,12p' "$0"; exit 1; }

# Fails with adb's own message (e.g. "device not found") instead of an empty screen.
adb get-state >/dev/null

# Prints "tag<TAB>text<TAB>description<TAB>x y" per element.
elements() {
    adb exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierchary dumped to.*//' | python3 -I -c '
import re, sys
import xml.etree.ElementTree as ET
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError:
    # No dump, e.g. the device is gone; adb reports that itself.
    sys.exit()
for node in root.iter("node"):
    # Page content comes as one long multi-line description; keep each field on one line.
    tag, text, desc = (" ".join(node.get(key, "").split()) for key in ("resource-id", "text", "content-desc"))
    if not (tag or text or desc):
        continue
    # Shortened, or a page of text would swamp the list.
    text, desc = (s if len(s) <= 80 else s[:79] + "…" for s in (text, desc))
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    print(f"{tag}\t{text}\t{desc}\t{(x1 + x2) // 2} {(y1 + y2) // 2}")
'
}

find() {
    elements | awk -F'\t' -v t="$1" '$1 == t || $2 == t || $3 == t { print; exit }'
}

case "${1:-}" in
    list) elements | column -t -s $'\t' ;;
    tap | hold)
        [ $# -eq 2 ] || usage
        match=$(find "$2")
        [ -n "$match" ] || { echo "Not on screen: $2" >&2; exit 1; }
        read -r x y <<<"$(cut -f4 <<<"$match")"
        if [ "$1" = tap ]; then adb shell input tap "$x" "$y"; else adb shell input swipe "$x" "$y" "$x" "$y" 800; fi
        ;;
    text)
        [ $# -eq 2 ] || usage
        match=$(find "$2")
        [ -n "$match" ] || { echo "Not on screen: $2" >&2; exit 1; }
        cut -f2 <<<"$match"
        ;;
    wait)
        [ $# -ge 2 ] || usage
        end=$((SECONDS + ${3:-10}))
        until [ -n "$(find "$2")" ]; do
            [ $SECONDS -lt $end ] || { echo "Timed out waiting for: $2" >&2; exit 1; }
            sleep 0.5
        done
        ;;
    shot)
        [ $# -eq 2 ] || usage
        adb exec-out screencap -p >"$2"
        ;;
    *) usage ;;
esac
