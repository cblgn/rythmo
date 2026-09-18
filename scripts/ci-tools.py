#!/usr/bin/env python3
"""Install Linux x86_64 CI tools, verifying pinned SHA-256 before extraction.

Development tools only; no changes to the Android runtime or dependencies.
To update, review the upstream release and change both version and checksum.
"""

import hashlib
import io
import argparse
from pathlib import Path
import platform
import tarfile
import urllib.request

TOOLS = {
    "gitleaks": (
        "https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_x64.tar.gz",
        "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb",
    ),
    "actionlint": (
        "https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz",
        "8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8",
    ),
    "osv-scanner": (
        "https://github.com/google/osv-scanner/releases/download/v2.6.0/osv-scanner_linux_amd64",
        "ca69b3d3cd08f889a49dc0a383122f71cc528b83803671df5fd87485b108",
    ),
}


def verified_payload(payload: bytes, expected_sha: str, name: str) -> bytes:
    if hashlib.sha256(payload).hexdigest() != expected_sha:
        raise ValueError(f"Checksum mismatch for {name}; nothing installed")
    return payload


def unpack_binary(payload: bytes, expected_sha: str, name: str) -> bytes:
    verified_payload(payload, expected_sha, name)
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:gz") as archive:
        member = archive.getmember(name)
        if not member.isfile():
            raise ValueError(f"Expected a regular binary for {name}")
        # Read just the named executable, never extract arbitrary archive paths.
        with archive.extractfile(member) as executable:
            return executable.read()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tools", nargs="*", choices=list(TOOLS))
    selected = parser.parse_args().tools or list(TOOLS)
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise SystemExit("These tools require Linux x86_64 (WSL or GitHub Ubuntu runner).")
    destination = Path(__file__).resolve().parents[1] / ".tools/ci"
    destination.mkdir(parents=True, exist_ok=True)
    for name in selected:
        url, digest = TOOLS[name]
        with urllib.request.urlopen(url, timeout=60) as response:
            payload = response.read()
        binary = unpack_binary(payload, digest, name) if url.endswith(".tar.gz") else verified_payload(payload, digest, name)
        path = destination / name
        path.write_bytes(binary)
        path.chmod(0o755)
        print(f"Installed {name} (SHA-256 verified)")


if __name__ == "__main__":
    main()
