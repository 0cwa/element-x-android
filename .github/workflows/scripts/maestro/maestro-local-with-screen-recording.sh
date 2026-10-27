#!/usr/bin/env bash

#
# Copyright (c) 2025 Element Creations Ltd.
# Copyright 2024 New Vector Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only.
# Please see LICENSE in the repository root for full details.
#

set -euo pipefail

if [[ $# != 1 || ! -f "$1" ]]; then
    echo "Usage: $0 path/to/app.apk" >&2
    exit 1
fi

# Fail before touching the emulator when local or CI account settings are missing.
source "$(dirname "${BASH_SOURCE[0]}")/prepare-env.sh"
export PATH="$HOME/.maestro/bin:$PATH"

# First we disable the onboarding flow on Chrome, which is a source of issues
# (see https://stackoverflow.com/a/64629745)
echo "Disabling Chrome onboarding flow"
adb shell am set-debug-app --persistent com.android.chrome
adb shell 'echo "chrome --disable-fre --no-default-browser-check --no-first-run" > /data/local/tmp/chrome-command-line'
adb shell am start -n com.android.chrome/com.google.android.apps.chrome.Main

adb install -r "$1"
echo "Starting the screen recording..."
adb push .github/workflows/scripts/maestro/local-recording.sh /data/local/tmp/
adb shell "chmod +x /data/local/tmp/local-recording.sh"
mkdir -p ~/.maestro/tests
# Start logcat in the background and save the output to a file, use `org.matrix.rust.sdk` tag since the SDK handles the logging
adb logcat 'org.matrix.rust.sdk:D *:S' > ~/.maestro/tests/logcat.txt &
LOGCAT_PID=$!
adb shell "/data/local/tmp/local-recording.sh & echo \$! > /data/local/tmp/screenrecord_pid.txt" &
RECORDING_ADB_PID=$!
set +e
maestro test .maestro/allTests.yaml
TEST_STATUS=$?
echo "Test run completed with status $TEST_STATUS"

# Stop the screen recording loop
SCRIPT_PID=$(adb shell "cat /data/local/tmp/screenrecord_pid.txt" | tr -d '\r')
if [[ "$SCRIPT_PID" =~ ^[0-9]+$ ]]; then
    adb shell kill -TERM "$SCRIPT_PID"
fi

# Get the PID of the screen recording process
SCREENRECORD_PID=$(adb shell pidof screenrecord | tr -d '\r')
# A stuck recorder must not turn a finished test into an indefinitely running job.
STOP_ATTEMPTS=0
while [[ -n "$SCREENRECORD_PID" && "$STOP_ATTEMPTS" -lt 10 ]]; do
  echo "Waiting for screen recording ($SCREENRECORD_PID) to finish..."
  for PID in $SCREENRECORD_PID; do
    [[ "$PID" =~ ^[0-9]+$ ]] && adb shell kill -2 "$PID"
  done
  sleep 1
  SCREENRECORD_PID=$(adb shell pidof screenrecord | tr -d '\r')
  STOP_ATTEMPTS=$((STOP_ATTEMPTS + 1))
done
if [[ -n "$SCREENRECORD_PID" ]]; then
  echo "Screen recording did not stop in time; continuing with the test result." >&2
fi

adb pull /data/local/tmp/recordings/ ~/.maestro/tests/
kill "$LOGCAT_PID" "$RECORDING_ADB_PID" 2>/dev/null
exit "$TEST_STATUS"
