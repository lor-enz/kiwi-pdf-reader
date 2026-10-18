#!/bin/bash
# Prepares a device or emulator for adb-driven testing: animations off, so screenshots and
# uiautomator dumps don't catch the UI mid-transition. Run it again after the emulator boots
# from an older snapshot, which brings the old settings back.
# Pick the device with ANDROID_SERIAL (e.g. ANDROID_SERIAL=emulator-5554).
set -euo pipefail

for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$setting" 0
done
echo "Animations off on $(adb get-serialno)"
