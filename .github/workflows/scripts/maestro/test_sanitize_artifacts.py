# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only.

import html
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("sanitize_artifacts", Path(__file__).with_name("sanitize-artifacts.py"))
sanitizer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sanitizer)


class SanitizeArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name) / "raw"
        self.source.mkdir()
        self.output = Path(self.temp.name) / "safe"
        self.password = 'sentinel-"p\\ä$ secret\n'
        self.key = "ABCD EFGH IJKL"

    def write(self, path, content):
        destination = self.source / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding="utf-8")

    def run_sanitizer(self):
        return sanitizer.sanitize(self.source, self.output, self.password, self.key)

    def test_redacts_plain_json_html_and_recovery_key_variants(self):
        self.write("maestro.log", self.password + html.escape(self.password) + self.key + self.key.replace(" ", ""))
        for ascii_only in (True, False):
            self.write(f"commands-{ascii_only}.json", json.dumps({"password": self.password, "key": self.key}, ensure_ascii=ascii_only))
        self.write("nested.log", json.dumps(json.dumps(self.password)))
        self.assertEqual(self.run_sanitizer(), 4)
        for path in self.output.rglob("*"):
            if path.is_file():
                text = path.read_text(encoding="utf-8")
                for value in sanitizer.secret_variants(self.password, self.key):
                    self.assertNotIn(value, text)
        self.assertEqual(json.loads((self.output / "commands-True.json").read_text()), {"password": "[REDACTED]", "key": "[REDACTED]"})

    def test_omits_recordings_and_automatic_screenshots(self):
        self.write("recordings/testRecording1.mp4", "unredactable video")
        self.write("screenshots/step-023.png", "unredactable screenshot")
        self.write("takeScreenshot/build/maestro/unreviewed.png", "unreviewed snapshot")
        self.write("takeScreenshot/build/maestro/150-Verify.png", "reviewed snapshot before key entry")
        self.assertEqual(self.run_sanitizer(), 1)
        self.assertTrue((self.output / "takeScreenshot/build/maestro/150-Verify.png").exists())

    def test_omits_credential_filenames_and_symlinks(self):
        self.write("ABCD EFGH IJKL.log", "do not publish credential filenames")
        (self.source / "outside.log").symlink_to(Path(self.temp.name) / "private.txt")
        self.assertEqual(self.run_sanitizer(), 0)

    def test_refuses_missing_credentials(self):
        with self.assertRaises(ValueError):
            sanitizer.sanitize(self.source, self.output, "", self.key)
        self.assertFalse(self.output.exists())

    def test_refuses_existing_output_without_removing_it(self):
        self.output.mkdir()
        preserved = self.output / "keep.txt"
        preserved.write_text("preserve")
        with self.assertRaises(FileExistsError):
            self.run_sanitizer()
        self.assertEqual(preserved.read_text(), "preserve")

    def test_refuses_output_inside_input(self):
        with self.assertRaises(ValueError):
            sanitizer.sanitize(self.source, self.source / "output", self.password, self.key)
        self.assertFalse((self.source / "output").exists())


if __name__ == "__main__":
    unittest.main()
