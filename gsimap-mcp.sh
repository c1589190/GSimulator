#!/bin/bash
# GSimulator MCP stdio→HTTP bridge
# Requires GSimulator HTTP MCP server already running on port 8720.
# Start it separately: java -jar gsim-app/target/gsim-app-*.jar --no-cli

MCP_URL="http://127.0.0.1:8720/mcp"

while IFS= read -r line; do
    [ -z "$line" ] && continue
    result=$(curl -s --max-time 30 -X POST "$MCP_URL" \
        -H "Content-Type: application/json" \
        -d "$line" 2>/dev/null)
    # Skip empty responses (notifications return 204 No Content)
    [ -n "$result" ] && echo "$result"
done
