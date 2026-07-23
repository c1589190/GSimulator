#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Kill any existing GSimulator process holding our ports
for port in 8710 8711 8712; do
    pid=$(lsof -ti:$port 2>/dev/null) || pid=$(fuser $port/tcp 2>/dev/null | tr -d ' ')
    if [ -n "$pid" ]; then
        echo "Killing existing process on port $port (PID $pid)..." >&2
        kill -9 $pid 2>/dev/null || true
    fi
done
sleep 0.5

# Build all modules (output to stderr to avoid corrupting MCP stdio)
echo "Building GSimulator..." >&2
mvn -q package -DskipTests -Dmaven.test.skip=true -pl gsim-lib,gsimap,gsim-app >&2 2>&1

# Launch full GSimulator in --no-cli mode
# This starts: MCP(stdio) + WebUI(8710) + Map(8711) + CLI-WS(8712)
exec java -Xmx4g -Xms512m \
    -Dgsimap.worldsDir="$SCRIPT_DIR/worlds" \
    -Dgsimap.importDir="$SCRIPT_DIR/import" \
    -Dlogback.configurationFile="$SCRIPT_DIR/gsimap/logback-mcp.xml" \
    -cp "gsim-app/target/gsim-app-0.1.0-Alpha260723.jar" \
    com.gsim.Main \
    --no-cli --no-wizard
