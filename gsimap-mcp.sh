#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/gsimap/target/gsimap-0.1.0-Alpha1.jar"
[ ! -f "$JAR" ] && { echo "Building..." >&2; cd "$SCRIPT_DIR" && mvn -q package -DskipTests -pl gsimap,gsim-lib 2>&1 >&2; }
exec java -Xmx4g -Xms512m \
    -Dapi.port=8709 \
    -Dapi.enabled=true \
    -Dgsimap.worldsDir="$SCRIPT_DIR/worlds" \
    -Dgsimap.gsimPort=8709 \
    -Dlogback.configurationFile="$SCRIPT_DIR/gsimap/logback-mcp.xml" \
    -jar "$JAR" --mcp-only
