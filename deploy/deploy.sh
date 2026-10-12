#!/usr/bin/env bash
# CubeOS dating backend -- ship the jar + config to the VPS and run remote.sh there.
# Uses ONE ssh connection (ufw rate-limits SSH).
#
#   deploy/deploy.sh                 # uses target/alovoa-1.1.0.jar (build first: mvn -q package)
#   HOST=root@1.2.3.4 KEY=~/.ssh/k JAR=/path/app.jar deploy/deploy.sh
set -euo pipefail

HOST=${HOST:-root@109.122.27.74}
KEY=${KEY:-$HOME/.ssh/cubeos_deploy}
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(dirname "$HERE")
JAR=${JAR:-$REPO/target/alovoa-1.1.0.jar}

if [ ! -f "$JAR" ]; then
    echo "jar not found: $JAR -- build it first: (cd $REPO && JAVA_HOME=~/.jdks/temurin-21 mvn -q package)" >&2
    exit 1
fi

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
cp "$JAR" "$STAGE/alovoa.jar"
cp "$HERE/remote.sh" "$HERE/dating.properties" "$HERE/cubeos-dating.service" \
   "$HERE/nginx-dating-locations.conf" "$HERE/nginx-dating-zones.conf" "$HERE/mariadb-cubeos.cnf" "$STAGE/"
echo "jar: $(sha256sum "$STAGE/alovoa.jar" | cut -d' ' -f1)  ($(du -h "$STAGE/alovoa.jar" | cut -f1))"
echo "commit: $(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)"

tar czf - -C "$STAGE" . | ssh -i "$KEY" -o BatchMode=yes -o ConnectTimeout=20 "$HOST" '
    set -e
    d=$(mktemp -d /tmp/cubeos-dating.XXXXXX)
    trap "rm -rf $d" EXIT
    tar xzf - -C "$d"
    bash "$d/remote.sh" "$d"
'
