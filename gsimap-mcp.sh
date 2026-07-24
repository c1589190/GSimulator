#!/bin/bash
set -e

# ── GSimulator MCP health-check launcher ───────────────────────
# This script no longer starts the full GSimulator — use Main.java
# directly for that. It only checks whether the HTTP MCP server is
# already running and prints the connection URL.
#
# Usage:
#   ./gsimap-mcp.sh          → check health, print URL if up
#   ./gsimap-mcp.sh --status → check and report status only

MCP_PORT="${MCP_HTTP_PORT:-8720}"
HEALTH_URL="http://127.0.0.1:${MCP_PORT}/health"
MCP_URL="http://127.0.0.1:${MCP_PORT}/mcp"

STATUS_ONLY=false
if [ "$1" = "--status" ]; then
    STATUS_ONLY=true
fi

# ── Check if server is running ──────────────────────────────────
if command -v curl &>/dev/null; then
    HEALTH_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" "$HEALTH_URL" 2>/dev/null || echo "000")
elif command -v wget &>/dev/null; then
    HEALTH_RESPONSE=$(wget -q -O /dev/null --timeout=2 "$HEALTH_URL" 2>&1 && echo "200" || echo "000")
else
    echo "⚠️  Neither curl nor wget found. Cannot check health." >&2
    echo "   Install curl or manually verify: $HEALTH_URL" >&2
    exit 1
fi

if [ "$HEALTH_RESPONSE" = "200" ]; then
    if $STATUS_ONLY; then
        echo "GSimulator MCP: RUNNING"
        echo "  Health: $HEALTH_URL"
        echo "  MCP:    $MCP_URL"
    else
        echo "✅ GSimulator MCP is already running." >&2
        echo "   MCP endpoint: $MCP_URL" >&2
        echo "   Health check: $HEALTH_URL" >&2
        echo "" >&2
        echo "Configure your MCP client with:" >&2
        echo "  url: $MCP_URL" >&2
    fi
    exit 0
else
    if $STATUS_ONLY; then
        echo "GSimulator MCP: NOT RUNNING (health check returned $HEALTH_RESPONSE)"
    else
        echo "❌ GSimulator MCP is NOT running (health: $HEALTH_RESPONSE)." >&2
        echo "" >&2
        echo "Start it first:" >&2
        echo "  cd $(dirname "$0") && mvn -q package -DskipTests" >&2
        echo "  java -jar gsim-app/target/gsim-app-*.jar --no-cli" >&2
    fi
    exit 1
fi
