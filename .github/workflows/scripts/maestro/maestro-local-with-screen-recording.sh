#!/usr/bin/env bash

#
# Copyright (c) 2025 Element Creations Ltd.
# Copyright 2024 New Vector Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only.
# Please see LICENSE in the repository root for full details.
#

set -euo pipefail

# Retain this legacy filename for queued workflows that still invoke it.
# Recordings are intentionally omitted: CI uploads only redacted diagnostics.

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
mkdir -p ~/.maestro/tests
# Start logcat in the background and save the output to a file, use `org.matrix.rust.sdk` tag since the SDK handles the logging
adb logcat 'org.matrix.rust.sdk:D *:S' > ~/.maestro/tests/logcat.txt &
LOGCAT_PID=$!
# Do not wait on diagnostics or let an already exited collector change the verdict.
trap 'kill "$LOGCAT_PID" 2>/dev/null || true' EXIT

TEST_STATUS=0
maestro test .maestro/allTests.yaml || TEST_STATUS=$?
echo "Test run completed with status $TEST_STATUS"
exit "$TEST_STATUS"
