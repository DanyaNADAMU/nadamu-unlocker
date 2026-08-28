#!/usr/bin/env python3
"""Documentation consistency linter.

Fails when code changes touch documented behavior without the corresponding
docs being updated in the same diff. Also validates doc hygiene: internal
links, status markers, and Verified: date freshness.

Exit codes: 0 = pass, 1 = violations found.

Usage:
    python3 scripts/check_docs.py                      # hygiene checks only
    python3 scripts/check_docs.py --base origin/main   # + trigger rules vs base
"""

import argparse
import datetime
import fnmatch
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# ---------------------------------------------------------------------------
# Rule 1: trigger paths -> docs that MUST be touched in the same change.
# Keep this in sync with AGENTS.md "Doc maintenance rules".
TRIGGER_RULES = [
    {
        "name": "unlock protocol (android app)",
        "code": ["android/app/src/**"],
        "docs": ["docs/en/unlock-flow.md", "docs/ru/unlock-flow.md"],
    },
    {
        "name": "network modes / lab topologies",
        "code": ["lab/**/*.sh", "lab/Containerfile", "lab/compose.yml",
                 "lab/*.py"],
        "docs": ["docs/en/network-modes.md", "docs/ru/network-modes.md"],
    },
    {
        "name": "laptop install / initramfs hooks",
        "code": ["laptop/**"],
        "docs": ["docs/en/unlock-flow.md", "docs/ru/unlock-flow.md", "docs/en/laptop-setup.md", "docs/ru/laptop-setup.md"],
    },
]

BYPASS_TOKEN = "[docs-ok]"

# ---------------------------------------------------------------------------
# Doc hygiene rules
STATUS_RE = re.compile(
    r"\[(implemented|planned|broken|in progress|in-progress)\]", re.IGNORECASE)
VERIFIED_RE = re.compile(r"Verified:\s*(\d{4}-\d{2}-\d{2}|never)", re.IGNORECASE)
MAX_VERIFIED_AGE_DAYS = 90


def sh(cmd: list[str]) -> str:
    return subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True,
                          text=True, check=True).stdout


def changed_files(base: str) -> list[str]:
    out = sh(["git", "diff", "--name-only", f"{base}...HEAD"])
    return sorted(set(out.splitlines()))


def commit_messages(base: str) -> str:
    try:
        return sh(["git", "log", "--format=%B", f"{base}..HEAD"])
    except subprocess.CalledProcessError:
        return ""


def any_match(globs: list[str], path: str) -> bool:
    for g in globs:
        if fnmatch.fnmatch(path, g):
            return True
        # handle ** by prefix/suffix decomposition
        if "**/" in g:
            prefix, suffix = g.split("**/", 1)
            if path.startswith(prefix) and (
                suffix == "" or fnmatch.fnmatch(path[len(prefix):], "*" + suffix)
                or fnmatch.fnmatch(path[len(prefix):], suffix)
            ):
                return True
    return False


def rule_violations(changed: list[str], bypass: bool) -> list[str]:
    problems = []
    if bypass:
        print(f"ℹ '{BYPASS_TOKEN}' found in commit messages — trigger rules skipped")
        return problems
    for rule in TRIGGER_RULES:
        hit_code = [f for f in changed if any_match(rule["code"], f)]
        if not hit_code:
            continue
        hit_docs = [f for f in changed if any_match(rule["docs"], f)]
        if hit_docs:
            continue
        sample = ", ".join(hit_code[:3]) + ("..." if len(hit_code) > 3 else "")
        problems.append(
            f"rule '{rule['name']}': {len(hit_code)} code file(s) changed "
            f"({sample}) but none of {rule['docs']} were updated. "
            f"Update them in this change, or add '{BYPASS_TOKEN}' to a "
            f"commit message if there is no doc impact."
        )
    return problems


def repo_md_files() -> list[Path]:
    skip = {".git"}
    files = []
    for p in REPO_ROOT.rglob("*.md"):
        if not (set(p.parts) & skip):
            files.append(p)
    return sorted(files)


def strip_inline_code(text: str) -> str:
    """Remove fenced blocks and `inline code` spans so documented EXAMPLES
    of markers (e.g. in conventions sections) are not treated as claims."""
    text = re.sub(r"```.*?```", "", text, flags=re.DOTALL)
    return re.sub(r"`[^`]*`", "", text)


def link_check() -> list[str]:
    problems = []
    for md in repo_md_files():
        text = md.read_text(encoding="utf-8")
        for m in re.finditer(r"\]\(([^)#\s]+?)(?:#[^)]*)?\)", text):
            target = m.group(1).strip()
            if target.startswith(("http://", "https://", "mailto:")):
                continue
            resolved = (md.parent / target).resolve()
            if not resolved.exists():
                rel = md.relative_to(REPO_ROOT)
                problems.append(f"{rel}: broken link to '{target}'")
    return problems


def verified_freshness() -> list[str]:
    problems = []
    today = datetime.date.today()
    for md in repo_md_files():
        text = strip_inline_code(md.read_text(encoding="utf-8"))
        statuses = [s.lower() for s in STATUS_RE.findall(text)]
        claims_done = any(s in ("implemented", "in progress", "in-progress")
                          for s in statuses)
        if not claims_done:
            continue
        rel = md.relative_to(REPO_ROOT)
        m = VERIFIED_RE.search(text)
        if not m:
            problems.append(
                f"{rel}: claims [implemented]/[in progress] but has no "
                f"'Verified: YYYY-MM-DD' marker")
            continue
        if m.group(1).lower() == "never":
            problems.append(
                f"{rel}: claims [implemented] but 'Verified: never' — "
                f"run the test and update the date, or demote to [planned]")
            continue
        d = datetime.date.fromisoformat(m.group(1))
        age = (today - d).days
        if age > MAX_VERIFIED_AGE_DAYS:
            problems.append(
                f"{rel}: 'Verified:' date is {age} days old "
                f"(>{MAX_VERIFIED_AGE_DAYS}) — re-verify or update the claim")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base", default=None,
                    help="git ref to compare against; enables trigger rules "
                         "(e.g. origin/main). Without it only doc-hygiene "
                         "checks run.")
    args = ap.parse_args()

    problems: list[str] = []

    if args.base:
        changed = changed_files(args.base)
        bypass = BYPASS_TOKEN in commit_messages(args.base)
        problems += rule_violations(changed, bypass)

    problems += link_check()
    problems += verified_freshness()

    if problems:
        print("✗ documentation linter failed:\n")
        for p in problems:
            print(f"  - {p}\n")
        return 1
    print("✓ documentation linter passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
