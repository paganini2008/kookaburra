#!/usr/bin/env sh
# Kookaburra Studio launcher. Data defaults to ./data next to this script (see kookaburra.properties).
#   ./studio.sh                 open the default data directory
#   ./studio.sh --demo          open an in-memory demo database
#   ./studio.sh --data=/path    open another database directory
DIR=$(cd "$(dirname "$0")" && pwd)
exec java ${JAVA_OPTS:--Xmx1g} -jar "$DIR/kookaburra-face.jar" "$@"
