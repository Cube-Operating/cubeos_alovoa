# cubeos-dating runbook

Cube App dating backend (Alovoa fork `cubeos_alovoa`, branch `cubeos/main`) on the CubeOS VPS
`109.122.27.74`. API base for the app: `http://109.122.27.74/dating/api/v1/cube`.

## Layout

| What | Where |
|---|---|
| jar (+ previous `alovoa.jar.prev`) | `/opt/cubeos/dating/alovoa.jar` |
| non-secret config | `/opt/cubeos/dating/dating.properties` (from `deploy/dating.properties`) |
| secrets | `/etc/cubeos/dating.env` (root:cubeos 0640, generated once) |
| working dir / tmp | `/var/lib/cubeos/dating` |
| unit | `cubeos-dating.service` (user `cubeos`, Java 21, `-Xmx768m`, `127.0.0.1:8098`) |
| database | MariaDB `cubeos_dating`, user `cubeos_dating` (localhost only), tuning in `/etc/mysql/mariadb.conf.d/60-cubeos.cnf` |
| nginx | `/etc/nginx/conf.d/cubeos-dating-zones.conf` (zones `dating` 120 r/min, `dating_media` 600 r/min), `/etc/nginx/snippets/cubeos-dating.conf` included in the :80 server of `/etc/nginx/sites-enabled/cubeos` |
| logs | `journalctl -u cubeos-dating` (no log files) |

Only `/dating/api/v1/cube/` is published by nginx (the `/dating` prefix is stripped). Alovoa's own web
UI (including `/admin` for reviewing reports) stays private: use
`ssh -L 8098:127.0.0.1:8098 root@109.122.27.74` and open `http://localhost:8098/`. The admin login is
`APP_ADMIN_EMAIL` / `APP_ADMIN_KEY` from `/etc/cubeos/dating.env`.

## Deploy / upgrade

```sh
cd backend/cubeos_alovoa
JAVA_HOME=~/.jdks/temurin-21 ~/.local/apache-maven-3.9.9/bin/mvn -q package   # tests need MariaDB, see CUBEOS.md
deploy/deploy.sh
```

`deploy.sh` packs the jar and the files in `deploy/` and runs `remote.sh` over a single SSH session.
`remote.sh` (idempotent):

1. Installs `mariadb-server` (and a Java 21 JRE, curl, openssl) if missing.
2. Installs the MariaDB tuning file and restarts MariaDB (bound to 127.0.0.1, 128 MB buffer pool).
3. Creates `/etc/cubeos/dating.env` with random DB password, text-encryption key/salt, admin key and
   remember-me key, **only if it doesn't exist** (upgrades keep it).
4. Creates the database and user (and resyncs the user's password from the env file).
5. Installs jar, properties and unit; restarts `cubeos-dating`.
6. Adds the nginx zones and the `/dating/` locations (backs up the site file to
   `<site>.bak-<timestamp>`, restores it if `nginx -t` fails), reloads nginx.
7. Smoke check: 401 JSON without a bearer (direct and through nginx), 401 for an invalid bearer
   (proves Cubemail is reachable; 502 would mean it isn't), 404 for `/dating/`, port 8098 not
   reachable from the public IP, registrar still answering.

Hibernate creates/updates the schema on start (`ddl-auto=update`), including the CubeOS table
`cube_account`.

## Operations

```sh
systemctl status cubeos-dating
journalctl -u cubeos-dating -f
curl -s http://127.0.0.1:8098/api/v1/cube/me            # {"error":...} 401 = up
mariadb cubeos_dating -e 'select count(*) from cube_account; select count(*) from cube_account where visible'
```

Rollback to the previous jar:

```sh
cp -p /opt/cubeos/dating/alovoa.jar.prev /opt/cubeos/dating/alovoa.jar && systemctl restart cubeos-dating
```

Remove the nginx publication: delete the `include /etc/nginx/snippets/cubeos-dating.conf;` line from
the site file, `nginx -t && systemctl reload nginx`.

Backups: `mariadb-dump cubeos_dating | gzip > dating-$(date +%F).sql.gz` **plus** `/etc/cubeos/dating.env`.
`APP_TEXT_KEY`/`APP_TEXT_SALT` encrypt stored e-mails (`<handle>@cubemail.io`) and names; a dump is
useless without them and changing them breaks every existing account.

## Resources

- Java: heap capped at 768 MB (`-Xmx768m`, SerialGC, metaspace 192 MB); measured RSS ~550 MB idle
  locally; systemd `MemoryMax=1300M`. Photo decoding is limited to 2 concurrent decodes and 40 MP.
- MariaDB: ~150–250 MB with the 128 MB buffer pool. Photos are stored in the DB (600×600 JPEG,
  ~50–100 KB each, max 6 per user), so ~0.5 MB per active user.
- Startup: ~10–30 s.

## Dependencies on the same host

- Cubemail `https://realiax.org/api/cubemail/wallet/me` validates bearer tokens (cached 5 min per token).
- Cubeline registrar `http://127.0.0.1/cubeline/me` (nginx → `cubeos-cubeline`) gives each user's
  Cubeline. Users without a Cubeline can't finish onboarding (409).
