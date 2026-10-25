#!/bin/bash
# The app's tasks (cards in the app switcher) and process death.
#
# Usage: scripts/test/app.sh <command>
#   cards        the app's cards, newest first, with their activities and the document each shows
#   focus <id>   switches to the card with that task id
#   top          the activity on screen and its task
#   kill         process death: home, then kill -9 (the cards stay, unlike with force-stop)
# Uses the running emulator, or the device in ANDROID_SERIAL.
set -euo pipefail
source "$(dirname "$0")/device.sh"

usage() { sed -n 's/^# \{0,1\}//; 2,9p' "$0"; exit 1; }

case "${1:-}" in
    cards)
        check_run_as
        RECENTS=$(adb shell dumpsys activity recents) \
        ACTIVITIES=$(adb shell dumpsys activity activities) \
        OPEN=$(adb shell run-as $PACKAGE cat files/recent/open.json 2>/dev/null || echo '{}') \
        INDEX=$(adb shell run-as $PACKAGE cat files/recent/index.json 2>/dev/null || echo '[]') \
        PACKAGE=$PACKAGE python3 -I - <<'EOF'
import json, os, re
package = os.environ["PACKAGE"]
tasks = re.findall(r"Recent #\d+: Task\{\w+ #(\d+) type=\w+ A=\d+:" + re.escape(package) + r"\}", os.environ["RECENTS"])
activities = {}
# Each task's stack, top first; the dump mentions activities in other places too.
for name, task in re.findall(r"Hist +#\d+: ActivityRecord\{\S+ \S+ " + re.escape(package) + r"/\.(\w+) t(\d+)", os.environ["ACTIVITIES"]):
    activities.setdefault(task, []).append(name)
names = {e["id"]: e["name"] for e in json.loads(os.environ["INDEX"] or "[]")}
open_entries = json.loads(os.environ["OPEN"] or "{}")
if not tasks:
    print("No cards")
for task in tasks:
    stack = " > ".join(reversed(activities.get(task, ["(none)"])))
    entry = open_entries.get(task)
    shows = names.get(entry, "(entry gone: " + entry + ")") if entry else ""
    print(f"#{task:<5} {stack:<38} {shows}")
EOF
        ;;
    focus)
        [ $# -eq 2 ] || usage
        adb shell cmd activity task focus "${2#\#}" >/dev/null
        ;;
    top)
        adb shell dumpsys activity activities | grep -m1 -oE "topResumedActivity=\S+ \S+ \S+ t[0-9]+" \
            | sed -E 's/.* ([^ ]+) (t[0-9]+)$/\1 in task #\2/; s/#t/#/'
        ;;
    kill)
        check_run_as
        adb shell input keyevent HOME
        sleep 1
        pid=$(adb shell pidof $PACKAGE || true)
        [ -n "$pid" ] || { echo "Not running"; exit 0; }
        adb shell run-as $PACKAGE kill -9 "$pid"
        echo "Killed $pid"
        ;;
    *) usage ;;
esac
