import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("sonar_mode", Path(__file__).parents[1] / "sonar_analysis_mode.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SonarModeTest(unittest.TestCase):
    def test_missing_token_refuses_analysis(self):
        with self.assertRaises(ValueError):
            module.enforce_ci_analysis("")

    def test_unknown_automatic_analysis_state_refuses_analysis(self):
        with patch.object(module, "request", return_value={"settings": []}), self.assertRaises(ValueError):
            module.automatic_enabled("test-token")

    def test_cannot_disable_automatic_analysis_refuses_scan(self):
        with patch.object(module, "automatic_enabled", return_value=True), patch.object(module, "request"), self.assertRaises(ValueError):
            module.enforce_ci_analysis("test-token")

    def test_redirect_never_forwards_credentials(self):
        handler = module.NoRedirects()
        self.assertIsNone(handler.redirect_request(None, None, 302, "redirect", {}, "https://other.invalid/"))
