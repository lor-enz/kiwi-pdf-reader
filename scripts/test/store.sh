#!/bin/bash
# Reads and changes the recent files list (files/recent/ in the app's private storage).
#
# Usage: scripts/test/store.sh <command>
#   show                the entries, newest first
#   age <name> <days>   makes an entry look last opened <days> ago, e.g. to test cleanup. Kills
#                       the app first (cards stay), as it keeps the list in memory; the next
#                       start reads the change.
# Uses the running emulator, or the device in ANDROID_SERIAL.
set -euo pipefail
source "$(dirname "$0")/device.sh"

usage() { sed -n 's/^# \{0,1\}//; 2,9p' "$0"; exit 1; }

index() { adb shell run-as $PACKAGE cat files/recent/index.json 2>/dev/null || echo '[]'; }

case "${1:-}" in
    show)
        INDEX=$(index) OPEN=$(adb shell run-as $PACKAGE cat files/recent/open.json 2>/dev/null || echo '{}') \
        python3 -I - <<'EOF'
import json, os, time

entries = json.loads(os.environ["INDEX"] or "[]")
open_in = {entry: task for task, entry in json.loads(os.environ["OPEN"] or "{}").items()}
now = time.time() * 1000


def ago(millis):
    minutes = (now - millis) / 60000
    return f"{minutes:.0f} min" if minutes < 60 else f"{minutes / 60:.0f} h" if minutes < 1440 else f"{minutes / 1440:.0f} d"


if not entries:
    print("No entries")
else:
    print(f"{'name':<28} {'kind':<6} {'opened':>7}  {'page':>7}  {'size':>8}  flags")
for e in entries:
    page = f"{e.get('lastPage', 0) + 1}/{e['pageCount']}" if e.get("pageCount") else "-"
    size = f"{e['sizeBytes'] / 1024:.0f} KB" if e.get("sizeBytes") else "-"
    flags = [flag for flag, on in [("thumbnail", e.get("hasThumbnail")), ("password", e.get("encryptedPassword")),
                                   ("hashed", e.get("contentHash")),
                                   ("filled", e.get("filledFromHash"))] if on]
    if e["id"] in open_in:
        flags.append("open in #" + open_in[e["id"]])
    kind = "copy" if e["isCopy"] else "picked"
    print(f"{e['name'][:28]:<28} {kind:<6} {ago(e['lastOpenedMillis']):>7}  {page:>7}  {size:>8}  {' '.join(flags)}")
EOF
        ;;
    age)
        [ $# -eq 3 ] || usage
        "$(dirname "$0")/app.sh" kill >/dev/null
        changed=$(index | NAME=$2 DAYS=$3 python3 -I -c '
import json, os, sys, time
entries = json.load(sys.stdin)
matches = [e for e in entries if e["name"] == os.environ["NAME"]]
if not matches:
    sys.exit("No entry named " + os.environ["NAME"])
for e in matches:
    e["lastOpenedMillis"] = int(time.time() * 1000 - float(os.environ["DAYS"]) * 86400000)
print(json.dumps(entries))
')
        tmp=/data/local/tmp/kiwi-index.json
        adb shell "cat > $tmp" <<<"$changed"
        adb shell "run-as $PACKAGE sh -c 'cat $tmp > files/recent/index.json'; rm $tmp"
        echo "$2 now last opened $3 days ago"
        ;;
    *) usage ;;
esac
