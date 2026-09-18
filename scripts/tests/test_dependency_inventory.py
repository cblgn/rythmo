import importlib.util
from pathlib import Path
import unittest
import subprocess
import sys
import tempfile
import json

spec = importlib.util.spec_from_file_location("dependency_inventory", Path(__file__).parents[1] / "dependency-inventory.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def snapshot(*dependencies):
    return {"manifests": {"gradle": {"resolved": {str(i): dep for i, dep in enumerate(dependencies)}}}}


class DependencyInventoryTest(unittest.TestCase):
    def test_cli_exports_to_stdout_and_rejects_an_output_path(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "snapshot.json"
            source.write_text(json.dumps(snapshot({"package_url": "pkg:maven/org.demo/app@1.0"})))
            script = str(Path(__file__).parents[1] / "dependency-inventory.py")
            result = subprocess.run([sys.executable, script, str(source)], capture_output=True, text=True, check=True)
            self.assertEqual(1, len(json.loads(result.stdout)["results"][0]["packages"]))
            target = Path(directory) / "must-not-be-written"
            rejected = subprocess.run([sys.executable, script, str(source), str(target)], capture_output=True)
            self.assertNotEqual(0, rejected.returncode)
            self.assertFalse(target.exists())

    def test_all_scopes_and_transitive_dependencies_are_retained(self):
        data = snapshot(
            {"package_url": "pkg:maven/org.demo/app@1.0", "scope": "runtime"},
            {"package_url": "pkg:maven/org.demo/plugin@2.0", "scope": "development"},
            {"package_url": "pkg:maven/org.demo/transitive@3.0", "relationship": "indirect"},
        )
        packages = module.inventory(data)["results"][0]["packages"]
        self.assertEqual(3, len(packages))

    def test_local_projects_skip_but_versions_do_not_collapse(self):
        data = snapshot({}, {"package_url": "pkg:maven/org.demo/lib@1.0"},
                        {"package_url": "pkg:maven/org.demo/lib@2.0"},
                        {"package_url": "pkg:maven/org.demo/lib@2.0?type=jar"})
        self.assertEqual(2, len(module.inventory(data)["results"][0]["packages"]))

    def test_decodes_package_version(self):
        data = snapshot({"package_url": "pkg:maven/org.demo/lib@1.0%2Bpatch?type=jar"})
        package = module.inventory(data)["results"][0]["packages"][0]["package"]
        self.assertEqual({"ecosystem": "Maven", "name": "org.demo:lib", "version": "1.0+patch"}, package)

    def test_missing_or_empty_inventory_fails_closed(self):
        for data in ({}, {"manifests": {}}, snapshot(), snapshot({})):
            with self.subTest(data=data), self.assertRaises(ValueError):
                module.inventory(data)

    def test_unknown_ecosystem_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "Unsupported"):
            module.inventory(snapshot({"package_url": "pkg:npm/new-library@1.0"}))

    def test_missing_version_fails_closed(self):
        with self.assertRaises(ValueError):
            module.inventory(snapshot({"package_url": "pkg:maven/org.demo/lib"}))
