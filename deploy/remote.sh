#!/usr/bin/env bash
# CubeOS dating backend -- install/upgrade on the VPS. Run as root by deploy.sh:
#   bash remote.sh <bundle-dir>
# Idempotent: re-running upgrades the jar/config and keeps the database and secrets.
set -euo pipefail

B="${1:-$(cd "$(dirname "$0")" && pwd)}"
APP_DIR=/opt/cubeos/dating
DATA_DIR=/var/lib/cubeos/dating
ENV_FILE=/etc/cubeos/dating.env
SITE=/etc/nginx/sites-enabled/cubeos
SNIPPET=/etc/nginx/snippets/cubeos-dating.conf
ZONES=/etc/nginx/conf.d/cubeos-dating-zones.conf
DB=cubeos_dating
DBUSER=cubeos_dating
PORT=8098
TS=$(date -u +%Y%m%dT%H%M%SZ)

log() { echo "==> $*"; }

[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }
for f in alovoa.jar dating.properties cubeos-dating.service nginx-dating-locations.conf nginx-dating-zones.conf mariadb-cubeos.cnf; do
    [ -f "$B/$f" ] || { echo "missing $B/$f" >&2; exit 1; }
done
id cubeos >/dev/null 2>&1 || { echo "user cubeos missing" >&2; exit 1; }

# --- 1. packages ------------------------------------------------------------------------------
export DEBIAN_FRONTEND=noninteractive
need=()
dpkg -s mariadb-server >/dev/null 2>&1 || need+=(mariadb-server)
if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | grep -qE 'version "(1[7-9]|2[0-9])'; then
    need+=(openjdk-21-jre-headless)
fi
command -v curl >/dev/null 2>&1 || need+=(curl)
command -v openssl >/dev/null 2>&1 || need+=(openssl)
if [ ${#need[@]} -gt 0 ]; then
    log "installing ${need[*]}"
    apt-get update -q
    apt-get install -y -q "${need[@]}"
fi

# --- 2. MariaDB (local only, small footprint) ---------------------------------------------------
install -m 644 "$B/mariadb-cubeos.cnf" /etc/mysql/mariadb.conf.d/60-cubeos.cnf
systemctl enable mariadb >/dev/null 2>&1 || true
systemctl restart mariadb
for i in $(seq 1 30); do mariadb -e 'SELECT 1' >/dev/null 2>&1 && break; sleep 1; done

# --- 3. secrets (generated once, kept on upgrade) -----------------------------------------------
if [ ! -f "$ENV_FILE" ]; then
    log "generating $ENV_FILE"
    umask 077
    cat > "$ENV_FILE" <<EOF
# CubeOS dating backend secrets (generated $TS). KEEP A BACKUP: APP_TEXT_KEY/APP_TEXT_SALT encrypt
# stored e-mails and names; losing or changing them makes existing accounts unreadable.
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_USERNAME=$DBUSER
SPRING_DATASOURCE_PASSWORD=$(openssl rand -hex 24)
APP_TEXT_KEY=$(openssl rand -hex 16)
APP_TEXT_SALT=$(openssl rand -hex 8)
APP_ADMIN_EMAIL=admin@dating.cubeos.invalid
APP_ADMIN_KEY=$(openssl rand -hex 24)
APP_LOGIN_REMEMBER_KEY=$(openssl rand -hex 32)
APP_DONATE_KOFI_KEY=$(openssl rand -hex 16)
APP_DONATE_BMAC_KEY=$(openssl rand -hex 16)
EOF
    umask 022
fi
chown root:cubeos "$ENV_FILE"
chmod 640 "$ENV_FILE"
DBPASS=$(grep '^SPRING_DATASOURCE_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)

# --- 4. database + user (password synced from the env file) ------------------------------------
log "database $DB"
mariadb <<SQL
CREATE DATABASE IF NOT EXISTS \`$DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$DBUSER'@'localhost' IDENTIFIED BY '$DBPASS';
CREATE USER IF NOT EXISTS '$DBUSER'@'127.0.0.1' IDENTIFIED BY '$DBPASS';
ALTER USER '$DBUSER'@'localhost' IDENTIFIED BY '$DBPASS';
ALTER USER '$DBUSER'@'127.0.0.1' IDENTIFIED BY '$DBPASS';
GRANT ALL PRIVILEGES ON \`$DB\`.* TO '$DBUSER'@'localhost';
GRANT ALL PRIVILEGES ON \`$DB\`.* TO '$DBUSER'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

# --- 5. application -------------------------------------------------------------------------------
log "installing jar to $APP_DIR"
install -d -m 755 "$APP_DIR"
install -d -m 750 -o cubeos -g cubeos "$DATA_DIR" "$DATA_DIR/tmp"
[ -f "$APP_DIR/alovoa.jar" ] && cp -p "$APP_DIR/alovoa.jar" "$APP_DIR/alovoa.jar.prev"
install -m 644 "$B/alovoa.jar" "$APP_DIR/alovoa.jar.new"
mv -f "$APP_DIR/alovoa.jar.new" "$APP_DIR/alovoa.jar"
install -m 644 "$B/dating.properties" "$APP_DIR/dating.properties"
install -m 644 "$B/cubeos-dating.service" /etc/systemd/system/cubeos-dating.service
systemctl daemon-reload
systemctl enable cubeos-dating >/dev/null
systemctl restart cubeos-dating

# --- 6. nginx: rate-limit zones + /dating/ locations -------------------------------------------
log "nginx"
SITE_REAL=$(readlink -f "$SITE")
# Backup OUTSIDE sites-enabled: nginx loads every file there, so a backup beside the site
# would be served as a second copy (duplicate server/locations) and fail nginx -t.
BACKUP=/root/nginx-$(basename "$SITE_REAL").bak-$TS
cp -p "$SITE_REAL" "$BACKUP"
install -m 644 "$B/nginx-dating-zones.conf" "$ZONES"
install -d /etc/nginx/snippets
install -m 644 "$B/nginx-dating-locations.conf" "$SNIPPET"
if ! grep -q "include $SNIPPET;" "$SITE_REAL"; then
    # into the first server{} (the :80 default_server), right before its catch-all "location / {"
    awk -v inc="    include $SNIPPET;" '
        !done && /^[[:space:]]*location \/ \{/ { print inc; done=1 }
        { print }
        END { if (!done) exit 3 }' "$SITE_REAL" > "$SITE_REAL.new" || { rm -f "$SITE_REAL.new"; echo "no 'location / {' in $SITE_REAL" >&2; exit 1; }
    cat "$SITE_REAL.new" > "$SITE_REAL"
    rm -f "$SITE_REAL.new"
fi
if ! nginx -t; then
    echo "nginx -t failed, restoring $SITE_REAL" >&2
    cp -p "$BACKUP" "$SITE_REAL"
    rm -f "$ZONES" "$SNIPPET"
    nginx -t && systemctl reload nginx
    exit 1
fi
systemctl reload nginx

# --- 7. smoke check -------------------------------------------------------------------------------
log "waiting for cubeos-dating on 127.0.0.1:$PORT"
ok=0
for i in $(seq 1 120); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/api/v1/cube/me" || true)
    if [ "$code" = 401 ]; then ok=1; break; fi
    if ! systemctl is-active --quiet cubeos-dating; then break; fi
    sleep 2
done
if [ $ok != 1 ]; then
    echo "cubeos-dating did not come up" >&2
    journalctl -u cubeos-dating -n 60 --no-pager >&2
    exit 1
fi
fail=0
check() { # name expected-code url [curl args...]
    local name=$1 want=$2 url=$3; shift 3
    local got body
    body=$(curl -s -w $'\n%{http_code}' "$@" "$url" || true)
    got=${body##*$'\n'}
    if [ "$got" = "$want" ]; then echo "  ok   $name ($got) ${body%$'\n'*}"; else echo "  FAIL $name: want $want got $got ${body%$'\n'*}"; fail=1; fi
}
check "direct, no bearer"          401 "http://127.0.0.1:$PORT/api/v1/cube/me"
check "nginx, no bearer"           401 "http://127.0.0.1/dating/api/v1/cube/me"
check "nginx, invalid bearer -> Cubemail rejects" 401 "http://127.0.0.1/dating/api/v1/cube/options" -H "Authorization: Bearer cubeos-smoke-invalid"
check "nginx, web UI not published" 404 "http://127.0.0.1/dating/"
check "not listening publicly"     000 "http://$(hostname -I | awk '{print $1}'):$PORT/" --connect-timeout 3
check "cubeline registrar still up" 401 "http://127.0.0.1/cubeline/me"
systemctl --no-pager --lines=0 status cubeos-dating | head -5
ps -o rss= -C java --sort=-rss | head -1 | awk '{printf "  largest java RSS: %d MB\n", $1/1024}'
[ $fail = 0 ] && log "cubeos-dating deployed" || { echo "smoke check failed" >&2; exit 1; }
