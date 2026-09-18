#!/usr/bin/env python3
"""Reject local records, spreadsheets and credentials from tracked source files."""

import argparse
from pathlib import PurePosixPath
import subprocess


def forbidden(path: str) -> bool:
    file = PurePosixPath(path)
    local_directories = {"server-data", "local-data", ".tools", ".android-user", ".codex", ".agents", ".gradle-user"}
    private_extensions = {".xls", ".xlsx", ".xlsm", ".xlsb", ".pdf", ".jks", ".keystore", ".apk", ".aab"}
    return (
        bool(local_directories.intersection(file.parts))
        or file.suffix.lower() in private_extensions
        or file.name == "local.properties"
        or file.name.endswith(":Zone.Identifier")
        or (file.name.startswith(".env") and file.name != ".env.example")
        or (path.startswith("data/rubrics/") and file.name.endswith(".draft.json"))
        or path == "scripts/extract-rubric.py"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", action="store_true", help="Check every fetched commit before publication")
    args = parser.parse_args()
    if args.history:
        commits = subprocess.check_output(["git", "rev-list", "--all"], text=True).splitlines()
        paths = set()
        for commit in commits:
            paths.update(subprocess.check_output(["git", "ls-tree", "-rz", "--name-only", commit]).decode().split("\0"))
    else:
        paths = set(subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0"))
    invalid = sorted(path for path in paths if path and forbidden(path))
    if invalid:
        print("Local/private files must not be published:")
        for path in invalid:
            print(repr(path))
        return 1
    print("No prohibited local files in the checked Git paths.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
