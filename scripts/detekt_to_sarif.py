#!/usr/bin/env python3
"""Convert a detekt checkstyle XML report into a GitHub code-scanning SARIF.

The detekt CLI (1.23.x) can emit txt / xml / html reports but not SARIF, so
this converter bridges the gap for the Quality workflow. Paths are made
repository-relative (CI checkouts live under /home/runner/work/<repo>/<repo>).
"""

from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET

from pathlib import Path


def repo_relative(path: str, repo_name: str) -> str:
    # GitHub runner: /home/runner/work/<repo>/<repo>/app/src/... -> app/src/...
    # Use the LAST occurrence of /<repo_name>/ so it also works for local
    # checkouts such as /home/user/projects/<repo>/app/src/...
    marker = f"/{repo_name}/"
    idx = path.rfind(marker)
    if idx != -1:
        return path[idx + len(marker):]
    return path.lstrip("/")


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: detekt_to_sarif.py <detekt.xml> <sarif-out> <repo-name>", file=sys.stderr)
        return 1
    src, dst, repo_name = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
    if not src.exists():
        print(f"no detekt XML report at {src}", file=sys.stderr)
        return 1

    results = []
    rules = {}
    for file_el in ET.parse(src).getroot().findall("file"):
        file_path = repo_relative(file_el.get("name", ""), repo_name)
        for err in file_el.findall("error"):
            rule_id = (err.get("source") or "detekt").removeprefix("detekt.")
            severity = err.get("severity", "warning")
            rules[rule_id] = {
                "id": rule_id,
                "shortDescription": {"text": err.get("message", rule_id)[:1024]},
            }
            results.append(
                {
                    "ruleId": rule_id,
                    "level": "error" if severity == "error" else "warning",
                    "message": {"text": err.get("message", "")},
                    "locations": [
                        {
                            "physicalLocation": {
                                "artifactLocation": {"uri": file_path},
                                "region": {
                                    "startLine": int(err.get("line", "1")),
                                    "startColumn": int(err.get("column", "1")),
                                },
                            }
                        }
                    ],
                }
            )

    sarif = {
        "$schema": "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json",
        "version": "2.1.0",
        "runs": [
            {
                "tool": {
                    "driver": {
                        "name": "detekt",
                        "version": "1.23.8",
                        "informationUri": "https://detekt.dev",
                        "rules": list(rules.values()),
                    }
                },
                "results": results,
            }
        ],
    }
    dst.write_text(json.dumps(sarif, indent=1))
    print(f"wrote {dst}: {len(results)} findings, {len(rules)} rules")
    return 0


if __name__ == "__main__":
    sys.exit(main())
