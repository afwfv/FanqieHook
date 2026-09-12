#!/usr/bin/env python3
"""Diff the ad-position constants in the `checkAdAvailableByAbTest` table against the
module's own BLOCKED_POSITIONS / PRESERVED_POSITIONS sets.

The module already documents that the position namespace is server-driven and that every
constant reaching checkAdAvailable was extracted and classified once. This script re-runs
that comparison for any APK so the classification can be re-checked after a host update
instead of being taken on faith.

usage: python audit_positions.py <apk> <ab_table_class::method> <AdHooks.kt path>
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

# Position constants look like lower_snake_case identifiers.
POSITION_RX = re.compile(r"^[a-z][a-z0-9]*(?:_[a-z0-9]+)+$")

# Strings in the table that are log tags / messages, not positions.
NOT_A_POSITION = {
    "checkAdAvailableByAbTest_position_is_empty",
}


def table_positions(apk: str, target: str) -> set[str]:
    out = subprocess.run(
        [sys.executable, "showmethod.py", apk, target, "2000"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout
    found: set[str] = set()
    for m in re.finditer(r"const-string '([^']*)'", out):
        s = m.group(1)
        if POSITION_RX.match(s) and s.replace("_", " ") not in NOT_A_POSITION:
            found.add(s)
    return found


def module_lists(kt: Path) -> tuple[set[str], set[str]]:
    """Pull the two sets out of AdHooks.kt without executing Kotlin."""
    text = kt.read_text(encoding="utf-8")

    def grab(name: str) -> set[str]:
        start = text.index(f"val {name} = setOf(")
        end = text.index("\n        )", start)
        body = text[start:end]
        # Strip comments before harvesting the quoted entries.
        body = re.sub(r"//[^\n]*", "", body)
        return set(re.findall(r'"([^"]+)"', body))

    return grab("BLOCKED_POSITIONS"), grab("PRESERVED_POSITIONS")


def main() -> int:
    apk, target, kt_path = sys.argv[1], sys.argv[2], Path(sys.argv[3])
    table = table_positions(apk, target)
    blocked, preserved = module_lists(kt_path)

    print(f"AB table positions : {len(table)}")
    print(f"BLOCKED_POSITIONS  : {len(blocked)}")
    print(f"PRESERVED_POSITIONS: {len(preserved)}")

    unclassified = sorted(table - blocked - preserved)
    print(f"\nUNCLASSIFIED ({len(unclassified)}):")
    for p in unclassified:
        print("   ", p)

    # Positions the module blocks that the AB table never mentions are worth a look too:
    # they may have been renamed or dropped by this host version.
    orphaned = sorted(blocked - table)
    print(f"\nblocked but absent from the AB table ({len(orphaned)}):")
    for p in orphaned:
        print("   ", p)
    return 0


if __name__ == "__main__":
    sys.exit(main())
