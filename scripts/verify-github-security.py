#!/usr/bin/env python3
"""Read back GitHub settings. Never changes settings or reads secret values.

Requires an authenticated maintainer `gh` session. Missing protection is always
an error, including plan limitations. Run after the public setup is applied.
"""

import json
from pathlib import Path
import subprocess

REPO = "cblgn/rythmo"


def api(endpoint: str):
    result = subprocess.run(
        ["gh", "api", f"repos/{REPO}/{endpoint}".rstrip("/")],
        capture_output=True, text=True, timeout=60, check=False,
    )
    if result.returncode:
        raise RuntimeError(f"Cannot verify {endpoint or 'repository'}")
    return json.loads(result.stdout) if result.stdout.strip() else None


def main() -> int:
    failures = []

    def check(label, actual, expected):
        ok = actual == expected
        print(f"{'PASS' if ok else 'FAIL'}: {label}")
        if not ok:
            failures.append(label)

    repo = api("")
    for name, value in {
        "visibility": "public", "default_branch": "main",
        "allow_squash_merge": True, "allow_merge_commit": False,
        "allow_rebase_merge": False, "allow_auto_merge": True,
        "delete_branch_on_merge": True,
    }.items():
        check(name, repo.get(name), value)
    actions = api("actions/permissions")
    for name, value in {"enabled": True, "allowed_actions": "selected", "sha_pinning_required": True}.items():
        check(name, actions.get(name), value)
    workflow = api("actions/permissions/workflow")
    check("Read-only default token", workflow.get("default_workflow_permissions"), "read")
    check("No automatic PR approvals", workflow.get("can_approve_pull_request_reviews"), False)
    allowed = api("actions/permissions/selected-actions")
    check("GitHub-owned actions", allowed.get("github_owned_allowed"), True)
    check("No blanket marketplace trust", allowed.get("verified_allowed"), False)
    check("Vendor allowlist", sorted(allowed.get("patterns_allowed", [])),
          sorted(["gradle/actions/*@*", "dependabot/fetch-metadata@*", "SonarSource/sonarqube-scan-action@*"]))
    api("vulnerability-alerts")  # 204 means enabled; permission/disabled errors fail.
    print("PASS: Dependabot alerts")
    fixes = api("automated-security-fixes")
    check("Security update PRs", fixes.get("enabled"), True)
    check("Security updates not paused", fixes.get("paused"), False)
    sbom = api("dependency-graph/sbom")
    check("Resolved dependencies in GitHub", len(sbom.get("sbom", {}).get("packages", [])) > 1, True)
    check("No open dependency vulnerabilities", api("dependabot/alerts?state=open&per_page=1"), [])
    security = repo.get("security_and_analysis", {})
    for feature in ("secret_scanning", "secret_scanning_push_protection"):
        check(feature, security.get(feature, {}).get("status"), "enabled")
    check("No open secret alerts", api("secret-scanning/alerts?state=open&per_page=1"), [])
    check("Private vulnerability reporting", api("private-vulnerability-reporting").get("enabled"), True)
    check("Main is protected", api("branches/main").get("protected"), True)
    check("Automatic dependency merge enabled", api("actions/variables/DEPENDABOT_AUTOMERGE").get("value"), "true")
    check("No duplicate CodeQL default setup", api("code-scanning/default-setup").get("state"), "not-configured")
    analyses = api("code-scanning/analyses?ref=refs/heads/main&per_page=100")
    head = api("commits/main")["sha"]
    for language in ("java-kotlin", "javascript-typescript", "python", "actions"):
        completed = any(a.get("category") == f"/language:{language}" and a.get("commit_sha") == head
                        and a.get("tool", {}).get("name") == "CodeQL" and not a.get("error") for a in analyses)
        check(f"CodeQL on current main: {language}", completed, True)
    check("No open CodeQL alerts", api("code-scanning/alerts?state=open&per_page=1"), [])

    rulesets = api("rulesets")
    if isinstance(rulesets, list):
        desired = json.loads((Path(__file__).resolve().parents[1] / ".github/rulesets/protect-main.json").read_text())
        matches = [r for r in rulesets if r["name"] == desired["name"]]
        check("Protect main ruleset present", len(matches), 1)
        if len(matches) == 1:
            actual = api(f"rulesets/{matches[0]['id']}")
            for key in ("target", "enforcement", "conditions", "bypass_actors"):
                check(f"Ruleset {key}", actual.get(key), desired[key])
            effective = api("rules/branches/main")
            for rule in desired["rules"]:
                found = [r for r in effective if r["type"] == rule["type"] and r.get("ruleset_id") == actual["id"]]
                check(f"Effective {rule['type']}", len(found), 1)
                if found:
                    for key, value in rule.get("parameters", {}).items():
                        actual_value = found[0].get("parameters", {}).get(key)
                        if isinstance(value, list):
                            value = sorted(json.dumps(v, sort_keys=True) for v in value)
                            actual_value = sorted(json.dumps(v, sort_keys=True) for v in actual_value) if isinstance(actual_value, list) else actual_value
                        check(f"Effective {rule['type']}.{key}", actual_value, value)
    else:
        check("Readable rulesets", False, True)
    return 1 if failures else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.TimeoutExpired) as error:
        raise SystemExit(str(error)) from error
