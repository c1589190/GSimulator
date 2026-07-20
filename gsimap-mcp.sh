#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Build all modules (gsim-lib + gsimap + gsim-app for the fat jar)
echo "Building GSimulator..."
mvn -q package -DskipTests -pl gsim-lib,gsimap,gsim-app 2>&1

# Launch MCP server via GsimMcpServer main()
# Uses the fat jar from gsim-app which bundles gsim-lib + gsimap dependencies
exec java -Xmx4g -Xms512m \
    -Dgsimap.worldsDir="$SCRIPT_DIR/worlds" \
    -Dgsimap.importDir="$SCRIPT_DIR/import" \
    -Dlogback.configurationFile="$SCRIPT_DIR/gsimap/logback-mcp.xml" \
    -cp "gsim-app/target/gsim-app-0.1.0-Alpha1.jar" \
    com.gsim.mcp.GsimMcpServer \
    "$SCRIPT_DIR/worlds" "$SCRIPT_DIR/import"
