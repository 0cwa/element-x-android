#!/usr/bin/env python3

# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only.
# Please see LICENSE in the repository root for full details.

"""Run with python3 -m unittest discover -s .github/workflows/scripts/maestro."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parent


class MaestroScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env_file = self.root / "github-env"
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("MAESTRO_") and key != "GITHUB_ENV"}
        self.env.update({
            "MAESTRO_APP_ID": "io.element.android.x.debug",
            "MAESTRO_USERNAME": "test-user",
            "MAESTRO_PASSWORD": "test-password-$(must-not-run)",
            "MAESTRO_RECOVERY_KEY": "test recovery key",
            "MAESTRO_ROOM_NAME": "A test room",
            "MAESTRO_INVITEE1_MXID": "@invitee-one:example.org",
            "MAESTRO_INVITEE2_MXID": "@invitee-two:example.org",
            "GITHUB_ENV": str(self.env_file),
        })

    def prepare(self):
        return subprocess.run(["bash", str(SCRIPTS / "prepare-env.sh")], env=self.env, capture_output=True, text=True, check=False)

    def assert_no_credentials_in_output(self, result):
        for name in ("MAESTRO_PASSWORD", "MAESTRO_RECOVERY_KEY"):
            self.assertNotIn(self.env[name], result.stdout + result.stderr)

    def test_full_matrix_id_derives_provider_and_localpart(self):
        self.env["MAESTRO_USERNAME"] = "@test-user:chat.example.org"
        result = self.prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        assignments = dict(line.split("=", 1) for line in self.env_file.read_text().splitlines())
        self.assertEqual(assignments["MAESTRO_USERNAME"], "test-user")
        self.assertEqual(assignments["MAESTRO_HOMESERVER"], "chat.example.org")
        self.assertEqual(assignments["MAESTRO_PASSWORD"], self.env["MAESTRO_PASSWORD"])
        self.assertEqual(assignments["MAESTRO_CLEANUP_SESSIONS"], "false")
        self.assertEqual(assignments["MAESTRO_LOCATION_SHARING_ENABLED"], "true")
        self.assert_no_credentials_in_output(result)

    def test_explicit_provider_is_preserved(self):
        self.env["MAESTRO_USERNAME"] = "@test-user:chat.example.org"
        self.env["MAESTRO_HOMESERVER"] = "https://matrix.example.org"
        self.assertEqual(self.prepare().returncode, 0)
        self.assertIn("MAESTRO_HOMESERVER=https://matrix.example.org\n", self.env_file.read_text())

    def test_empty_settings_fail_without_exporting_secrets(self):
        self.env["MAESTRO_ROOM_NAME"] = "  "
        result = self.prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("MAESTRO_ROOM_NAME", result.stderr)
        self.assertFalse(self.env_file.exists())
        self.assert_no_credentials_in_output(result)

    def test_multiline_cannot_inject_environment_assignment(self):
        self.env["MAESTRO_PASSWORD"] = "test-password\nINJECTED=value"
        result = self.prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("MAESTRO_PASSWORD", result.stderr)
        self.assertFalse(self.env_file.exists())
        self.assert_no_credentials_in_output(result)

    def test_cleanup_requires_explicit_boolean(self):
        self.env["MAESTRO_CLEANUP_SESSIONS"] = "yes"
        result = self.prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("MAESTRO_CLEANUP_SESSIONS", result.stderr)
        self.assertFalse(self.env_file.exists())

    def test_location_sharing_rejects_invalid_boolean_before_export(self):
        for value in ("", "yes", "TRUE", "false\nINJECTED=value"):
            with self.subTest(value=value):
                self.env["MAESTRO_LOCATION_SHARING_ENABLED"] = value
                result = self.prepare()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("MAESTRO_LOCATION_SHARING_ENABLED", result.stderr)
                self.assertFalse(self.env_file.exists())
                self.assert_no_credentials_in_output(result)

    def run_mocked_runner(self, status):
        # Isolate only the fixed output directory; execute the runner's real shell
        # logic with mocked adb/Maestro commands, without touching a device or HOME.
        runner = self.root / "runner.sh"
        runner.write_text((SCRIPTS / "maestro-local-with-screen-recording.sh").read_text().replace("~/.maestro/tests", '"$MOCK_TEST_OUTPUT"'))
        (self.root / "prepare-env.sh").write_text((SCRIPTS / "prepare-env.sh").read_text())
        apk = self.root / "app debug.apk"
        apk.touch()
        self.env.update({
            "MOCK_TEST_OUTPUT": str(self.root / "test-output"),
            "MOCK_ADB_CALLS": str(self.root / "adb-calls"),
            "MOCK_INSTALLED_APK": str(self.root / "installed-apk"),
            "MOCK_MAESTRO_STATUS": str(status),
            "MOCK_LOCATION_SHARING_ENABLED": str(self.root / "location-sharing-enabled"),
            "MOCK_LOGCAT_CLEANUP": str(self.root / "logcat-cleanup"),
        })
        mock_commands = """
            adb() {
                printf '%s\\n' "$*" >> "$MOCK_ADB_CALLS"
                case "$*" in
                    'install -r '*) printf '%s\\n' "$3" > "$MOCK_INSTALLED_APK" ;;
                esac
                return 0
            }
            maestro() {
                printenv MAESTRO_LOCATION_SHARING_ENABLED > "$MOCK_LOCATION_SHARING_ENABLED"
                return "$MOCK_MAESTRO_STATUS"
            }
            kill() {
                : > "$MOCK_LOGCAT_CLEANUP"
                return 8
            }
            source "$1" "$2"
        """
        result = subprocess.run(["bash", "-c", mock_commands, "mock-runner", str(runner), str(apk)],
                                env=self.env, capture_output=True, text=True, check=False, timeout=10)
        return result, apk

    def test_runner_preserves_failed_test_when_logcat_cleanup_fails(self):
        result, apk = self.run_mocked_runner(7)
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertEqual((self.root / "installed-apk").read_text().strip(), str(apk))
        self.assertTrue((self.root / "logcat-cleanup").exists())
        self.assert_no_credentials_in_output(result)

    def test_runner_preserves_success_when_logcat_cleanup_fails(self):
        result, _ = self.run_mocked_runner(0)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / "logcat-cleanup").exists())

    def test_runner_exports_the_apk_location_capability_to_maestro(self):
        for value in ("true", "false"):
            with self.subTest(value=value):
                self.env["MAESTRO_LOCATION_SHARING_ENABLED"] = value
                result, _ = self.run_mocked_runner(0)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual((self.root / "location-sharing-enabled").read_text().strip(), value)

    def test_runner_rejects_missing_config_before_adb(self):
        self.env["MAESTRO_PASSWORD"] = ""
        result, _ = self.run_mocked_runner(0)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("MAESTRO_PASSWORD", result.stderr)
        self.assertFalse((self.root / "adb-calls").exists())


if __name__ == "__main__":
    unittest.main()
