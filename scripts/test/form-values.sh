#!/bin/bash
# Prints the form field values of a PDF on the device, read with qpdf, and whether it needs a
# password. The PDF is the stored filled-in version of a recent entry, or a file on the device.
#
# Usage: scripts/test/form-values.sh [--password <pw>] <name of a recent entry | /device/path.pdf>
# Uses the running emulator, or the device in ANDROID_SERIAL.
set -euo pipefail
source "$(dirname "$0")/device.sh"

usage() { sed -n 's/^# \{0,1\}//; 2,6p' "$0"; exit 1; }

password=
if [ "${1:-}" = --password ]; then
    password=${2:-}
    shift 2
fi
[ $# -eq 1 ] || usage

local=$(mktemp --suffix=.pdf)
trap 'rm -f "$local"' EXIT
if [[ "$1" == /* ]]; then
    adb shell cat "'$1'" >"$local"
else
    id=$(adb shell run-as $PACKAGE cat files/recent/index.json | NAME=$1 python3 -I -c '
import json, os, sys
ids = [e["id"] for e in json.load(sys.stdin) if e["name"] == os.environ["NAME"]]
print(ids[0] if ids else "")')
    [ -n "$id" ] || { echo "No entry named $1" >&2; exit 1; }
    adb shell run-as $PACKAGE ls "files/recent/filled/$id.pdf" >/dev/null 2>&1 ||
        { echo "$1 has no filled-in version" >&2; exit 1; }
    adb exec-out run-as $PACKAGE cat "files/recent/filled/$id.pdf" >"$local"
fi

if qpdf --requires-password "$local"; then echo "needs a password"; else echo "no password"; fi
qpdf ${password:+--password="$password"} --json --json-key=acroform "$local" >"$local.json"
trap 'rm -f "$local" "$local.json"' EXIT
python3 -I - "$local.json" <<'EOF'
import json, sys

for field in json.load(open(sys.argv[1]))["acroform"]["fields"]:
    value = field["value"]
    if isinstance(value, str) and value.startswith("u:"):
        value = value[2:]
    # Radio buttons share a name; each widget has its own state.
    if field["fieldtype"] == "/Btn" and field.get("appearancestate") not in (None, "/Off"):
        value = field["appearancestate"]
    print(f"{field['fullname']} = {value}")
EOF
