import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import unittest

spec = importlib.util.spec_from_file_location("ci_tools", Path(__file__).parents[1] / "ci-tools.py")
ci_tools = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci_tools)


def archive_with(name="scanner", symlink=False):
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode="w:gz") as archive:
        member = tarfile.TarInfo(name)
        member.size = 2
        if symlink:
            member.type = tarfile.SYMTYPE
            member.linkname = "/tmp/untrusted"
        archive.addfile(member, io.BytesIO(b"ok"))
    payload = data.getvalue()
    return payload, hashlib.sha256(payload).hexdigest()


class ToolIntegrityTest(unittest.TestCase):
    def test_verified_regular_binary(self):
        payload, digest = archive_with()
        self.assertEqual(b"ok", ci_tools.unpack_binary(payload, digest, "scanner"))

    def test_tampered_archive_rejected_before_opening(self):
        with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
            ci_tools.unpack_binary(b"not an archive", "0" * 64, "scanner")

    def test_symlink_rejected(self):
        payload, digest = archive_with(symlink=True)
        with self.assertRaisesRegex(ValueError, "regular binary"):
            ci_tools.unpack_binary(payload, digest, "scanner")

    def test_traversal_member_not_extracted(self):
        payload, digest = archive_with("../scanner")
        with self.assertRaises(KeyError):
            ci_tools.unpack_binary(payload, digest, "scanner")
