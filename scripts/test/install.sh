#!/bin/bash
# Builds the debug version and installs it on the emulator (or ANDROID_SERIAL), never on every
# connected device like a plain `./gradlew installDebug`.
#
# Usage: scripts/test/install.sh
set -euo pipefail
source "$(dirname "$0")/device.sh"

cd "$(dirname "$0")/../.."
./gradlew installDebug --console=plain -q
echo "Installed on $ANDROID_SERIAL"
