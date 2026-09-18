"""Enforce CI-only Sonar analysis before sending sources or coverage."""

import json
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener

BASE_URL = "https://sonarcloud.io/api/"
PROJECT = "cblgn_rythmo"


class NoRedirects(HTTPRedirectHandler):
    """Never forward the Sonar credential to a redirect destination."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(endpoint, token, data=None):
    headers = {"Authorization": f"Bearer {token}"}
    body = urlencode(data).encode() if data is not None else None
    req = Request(BASE_URL + endpoint, data=body, headers=headers)
    with build_opener(NoRedirects()).open(req, timeout=30) as response:
        payload = response.read()
    return json.loads(payload) if payload else None


def automatic_enabled(token):
    query = urlencode({"component": PROJECT, "keys": "sonar.autoscan.enabled"})
    result = request("settings/values?" + query, token)
    settings = result.get("settings", [])
    values = [item.get("value") for item in settings if item.get("key") == "sonar.autoscan.enabled"]
    if len(values) != 1 or values[0] not in ("true", "false"):
        raise ValueError("Automatic Analysis state is missing or invalid; refusing CI analysis")
    return values[0] == "true"


def enforce_ci_analysis(token):
    if not token:
        raise ValueError("SONAR_TOKEN is required")
    if automatic_enabled(token):
        # This is the Sonar UI's internal endpoint, not a stable public API.
        # Fail closed if it changes or the credential cannot administer this project.
        request("autoscan/activation", token, {"projectKey": PROJECT, "enable": "false"})
        if automatic_enabled(token):
            raise ValueError("Automatic Analysis remains enabled; refusing CI analysis")
    print("Verified: Automatic Analysis is disabled; CI analysis may proceed.")


if __name__ == "__main__":
    try:
        enforce_ci_analysis(os.environ.get("SONAR_TOKEN", ""))
    except HTTPError as error:
        # Never print request headers, response bodies or credentials.
        print(f"Sonar analysis-mode API failed (HTTP {error.code}); scan blocked.", file=sys.stderr)
        sys.exit(1)
    except (URLError, ValueError, TypeError, AttributeError, KeyError):
        print("Cannot verify CI-only Sonar analysis; scan blocked.", file=sys.stderr)
        sys.exit(1)
