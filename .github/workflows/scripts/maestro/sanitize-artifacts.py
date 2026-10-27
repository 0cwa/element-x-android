#!/usr/bin/env python3

# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only.

"""Copy Maestro diagnostics for upload without resolved test credentials."""

import argparse
import html
import json
import os
from pathlib import Path
import shutil


# These explicit snapshots are taken before credential entry or after verification.
# Automatic failure screenshots and recordings can capture any intermediate screen.
SAFE_SCREENSHOTS = {
    "000-FirstScreen.png", "100-SignIn.png", "150-Verify.png",
    "300-RoomList.png", "310-RoomList-ContextMenu.png", "320-createAndDeleteRoom.png",
    "330-createAndDeleteDM.png", "400-SearchRoom.png", "500-Timeline.png",
    "510-Timeline.png", "511-Timeline.png", "520-Timeline.png", "521-Timeline.png",
    "530-Timeline.png", "531-Timeline.png", "600-Settings.png", "610-AccountEditProfile.png",
    "700-Call.png", "710-Call.png", "900-SignOutScreen.png",
}


def secret_variants(password: str, recovery_key: str) -> list[str]:
    compact_key = "".join(recovery_key.split())
    values = {
        password,
        recovery_key,
        compact_key,
        " ".join(compact_key[i:i + 4] for i in range(0, len(compact_key), 4)),
    }
    variants = set()
    for value in values - {""}:
        variants.update((value, html.escape(value)))
        for ascii_only in (True, False):
            escaped = json.dumps(value, ensure_ascii=ascii_only)[1:-1]
            variants.update((escaped, json.dumps(escaped, ensure_ascii=ascii_only)[1:-1]))
    return sorted(variants, key=len, reverse=True)


def sanitize(source: Path, destination: Path, password: str, recovery_key: str) -> int:
    if not password or not recovery_key:
        raise ValueError("Credential environment is unavailable; refusing to publish diagnostics.")
    if not source.is_dir():
        return 0
    if destination.resolve().is_relative_to(source.resolve()):
        raise ValueError("The upload directory must be outside the raw diagnostics directory.")
    # Refuse to mix safe output with a previous directory whose contents are unknown.
    destination.mkdir(parents=True, exist_ok=False)
    variants = secret_variants(password, recovery_key)
    count = 0
    for path in source.rglob("*"):
        if path.is_symlink() or not path.is_file():
            continue
        relative = path.relative_to(source)
        if any(value in str(relative) for value in variants):
            continue
        target = destination / relative
        if path.suffix.lower() in {".json", ".log", ".txt"}:
            text = path.read_text(encoding="utf-8")
            for value in variants:
                text = text.replace(value, "[REDACTED]")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
        elif "takeScreenshot" in relative.parts and path.name in SAFE_SCREENSHOTS:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
        else:
            continue
        count += 1
    return count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    try:
        count = sanitize(
            args.source,
            args.destination,
            os.environ.get("MAESTRO_PASSWORD", ""),
            os.environ.get("MAESTRO_RECOVERY_KEY", ""),
        )
    except (OSError, ValueError):
        raise SystemExit("Could not sanitize Maestro diagnostics; do not upload the raw files.") from None
    print(f"Prepared {count} sanitized diagnostics files; omitted recordings and automatic screenshots.")


if __name__ == "__main__":
    main()
