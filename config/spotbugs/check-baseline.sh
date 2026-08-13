#!/usr/bin/env bash
# SpotBugs ratchet gate — compares current findings against baseline.
# Exits 0 if no NEW bugs were introduced (only known/removed bugs).
# Exits 1 if new bugs are found.
#
# Usage:  ./config/spotbugs/check-baseline.sh <module>
#   ./config/spotbugs/check-baseline.sh gsim-core
#
# Requires: xmlstarlet or python3 for XML diff.
# Baseline: config/spotbugs/<module>-baseline.xml

set -euo pipefail

MODULE="${1:-gsim-core}"
BASELINE="config/spotbugs/${MODULE}-baseline.xml"
CURRENT="${MODULE}/target/spotbugsXml.xml"

if [ ! -f "$BASELINE" ]; then
    echo "::error:: Baseline not found at $BASELINE"
    exit 2
fi

if [ ! -f "$CURRENT" ]; then
    echo "::error:: SpotBugs XML not found at $CURRENT. Run 'mvn spotbugs:spotbugs -pl $MODULE' first."
    exit 2
fi

# Extract BugInstance fingerprints: type + class + method + field + sourceLine
# We use Python for reliable XML parsing.
FINGERPRINTS=$(python3 - "$BASELINE" "$CURRENT" <<'PYEOF'
import sys, xml.etree.ElementTree as ET

def fingerprints(path):
    tree = ET.parse(path)
    bugs = []
    for bi in tree.findall('.//BugInstance'):
        # Normalize: type@class.method(field):line
        t = bi.get('type', '')
        cls = bi.get('class', '') or ''
        mtd = bi.get('method', '') or ''
        fld = bi.get('field', '') or ''
        src = bi.find('SourceLine')
        line = src.get('start', '') if src is not None else ''
        fp = f"{t}@{cls}.{mtd}({fld}):{line}"
        bugs.append(fp)
    return set(bugs)

baseline = fingerprints(sys.argv[1])
current  = fingerprints(sys.argv[2])

new_bugs = current - baseline
removed = baseline - current

if new_bugs:
    print(f"NEW_BUGS={len(new_bugs)}")
    for b in sorted(new_bugs):
        print(f"  + {b}")
    print(f"REMOVED={len(removed)}")
    for b in sorted(removed):
        print(f"  - {b}")
    sys.exit(1)
else:
    print(f"OK: no new bugs (baseline={len(baseline)}, current={len(current)}, removed={len(removed)})")
    sys.exit(0)
PYEOF
)

exit $?
