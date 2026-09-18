import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("public_files", Path(__file__).parents[1] / "check-public-files.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PublicFilesTest(unittest.TestCase):
    def test_rejects_records_credentials_and_all_excel_formats(self):
        for path in ("data/grades.XLSX", "grades.xls", "grades.xlsm", "grades.xlsb",
                     "server-data/teacher.json", "reports/student.pdf", ".env.production",
                     "local.properties", ".tools/key", "release.keystore", "file:Zone.Identifier",
                     "local-data/workbook.json", "data/rubrics/private.draft.json", "scripts/extract-rubric.py"):
            with self.subTest(path=path):
                self.assertTrue(module.forbidden(path))

    def test_source_and_generic_rubric_json_are_allowed(self):
        for path in ("core/src/test/Test.kt", "data/rubrics/example.json", ".env.example", "docs/baremes.md"):
            with self.subTest(path=path):
                self.assertFalse(module.forbidden(path))
