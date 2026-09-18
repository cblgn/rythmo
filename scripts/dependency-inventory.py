#!/usr/bin/env python3
"""Convert the complete resolved Gradle snapshot to OSV's custom lockfile format.

Includes runtime, build and test dependencies. Fails on empty/unknown input;
vulnerability lookup and exit status are handled by the official OSV scanner.
"""

import argparse
import json
import sys
from pathlib import Path
from urllib.parse import unquote


def inventory(snapshot: dict) -> dict:
    manifests = snapshot.get("manifests")
    if not isinstance(manifests, dict) or not manifests:
        raise ValueError("No manifests in the dependency snapshot")
    packages = {}
    for manifest in manifests.values():
        for dependency in manifest["resolved"].values():
            purl = dependency.get("package_url")
            if not purl:  # Local Gradle projects have no published package URL.
                continue
            if not purl.startswith("pkg:maven/"):
                raise ValueError(f"Unsupported package ecosystem: {purl}")
            coordinate, version = purl.removeprefix("pkg:maven/").split("@", 1)
            group, artifact = coordinate.split("/", 1)
            name = unquote(group) + ":" + unquote(artifact)
            version = unquote(version.split("?", 1)[0].split("#", 1)[0])
            if not group or not artifact or not version:
                raise ValueError(f"Missing package name or version: {purl}")
            packages[(name, version)] = {"package": {"ecosystem": "Maven", "name": name, "version": version}}
    if not packages:
        raise ValueError("No resolved packages; refusing an empty security audit")
    return {"results": [{"packages": [packages[key] for key in sorted(packages)]}]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path)
    args = parser.parse_args()
    data = inventory(json.loads(args.snapshot.read_text()))
    print(json.dumps(data, indent=2))
    print(f"Exported {len(data['results'][0]['packages'])} resolved packages for OSV.", file=sys.stderr)


if __name__ == "__main__":
    main()
