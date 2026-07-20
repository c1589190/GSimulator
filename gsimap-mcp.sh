#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Build all modules
echo "Building GSimulator..."
mvn -q package -DskipTests -pl gsim-lib,gsimap,gsim-app 2>&1

# Launch full GSimulator in --no-cli mode
# This starts: MCP(stdio) + WebUI(8710) + Map(8711) + CLI-WS(8712)
exec java -Xmx4g -Xms512m \
    -Dgsimap.worldsDir="$SCRIPT_DIR/worlds" \
    -Dgsimap.importDir="$SCRIPT_DIR/import" \
    -Dlogback.configurationFile="$SCRIPT_DIR/gsimap/logback-mcp.xml" \
    -cp "gsim-app/target/gsim-app-0.1.0-Alpha1.jar" \
    com.gsim.Main \
    --no-cli --no-wizard
