# CubeOS changes to Alovoa

This fork (`Cube-Operating/cubeos_alovoa`, branch `cubeos/main`) turns Alovoa into the **Cube App
dating backend**. Identity comes from Cube App (Cubemail bearer tokens), discovery is location-based,
and **all messaging goes through Cube App's Session messenger**: a mutual match reveals each other's
Cubeline and the app opens the encrypted Session chat by Cubeline. Alovoa's own chat is not used for
Cube App users. Upstream code is kept; CubeOS code lives in `com.nonononoki.alovoa.cube`.

License: AGPL-3.0 (unchanged). The deployed service must keep offering its source (this repository).

## Upstream files touched

| File | Change |
|---|---|
| `src/main/resources/application.properties` | one line: `spring.config.import=optional:classpath:cube.properties` |

Everything else is new.

## New files

| Path | Purpose |
|---|---|
| `src/main/resources/cube.properties` | CubeOS settings (Cubemail/registrar URLs, token cache, photo URL prefix, location max age) and a few upstream overrides: `app.profile.image.max=5` (6 photos incl. profile picture), `app.search.max.distance=200`, `app.first-name.length-max=30`, `server.forward-headers-strategy=native` |
| `cube/CubeJpaConfig.java` | registers the cube entity/repository packages (AlovoaApplication pins its scan packages) |
| `cube/auth/CubeIdentityClient.java` | Cubemail token validation (same rules as `cubemail_account` in `backend/cubeline-registrar/registrar.py`), SHA-256-keyed token cache (5 min), Cubeline lookup via the registrar `GET /cubeline/me` |
| `cube/auth/CubemailBearerFilter.java` | bearer filter for `/api/v1/cube/**`: validates the token, auto-creates the Alovoa user on first use, runs the request as that user (principal = user UUID) |
| `cube/auth/CubeSecurityConfig.java` | separate `@Order(1)` stateless `SecurityFilterChain` for `/api/v1/cube/**` (no session, no cookies, CSRF/form login/remember-me off). Alovoa's own chain and web login are unchanged |
| `cube/entity/CubeAccount.java`, `cube/repo/CubeAccountRepository.java` | side table `cube_account` keyed by user id: Cubeline, Cubeline check time, location update time, `visible` opt-in flag |
| `cube/service/CubeAccountService.java` | provisioning (`<handle>@cubemail.io`, random password, confirmed, no captcha, no e-mail notifications), Cubeline refresh, account deletion (Alovoa's deletion cascade, transactional) |
| `cube/service/CubeAccess.java` | shared visibility rules (onboarded, opted-in, blocks, mutual match) |
| `cube/service/CubeProfileService.java` | `/me`, `/options`, onboarding (18+), profile edits, photos, location, visibility |
| `cube/service/CubeDiscoverService.java` | discovery on top of Alovoa's `SearchService.searchComplete` |
| `cube/service/CubeMatchService.java` | like/pass/matches/unmatch/block/report |
| `cube/service/CubePhotoService.java` | bearer-protected photo bytes |
| `cube/service/CubeImageNormalizer.java` | photo validation (JPEG/PNG/WebP by signature, ≤ 5 MB, ≤ 40 MP) and 600×600 centre crop → JPEG |
| `cube/service/CubeGeo.java` | coordinate rounding (2 decimals) and whole-km distances |
| `cube/service/CubeLocationJanitor.java` | hourly: clears locations older than 30 days |
| `cube/service/CubeViews.java` | JSON shapes; never emits emails, handles or coordinates |
| `cube/web/CubeController.java`, `CubeApiException.java`, `CubeExceptionHandler.java` | the API and `{"error": ...}` errors (scoped to the cube package) |
| `src/test/java/.../cube/*` | unit + integration tests (see below) |
| `deploy/*` | VPS deploy bundle (`deploy.sh`, `remote.sh`, unit, nginx snippets, MariaDB tuning, `RUNBOOK.md`) |

## Identity bridge

`Authorization: Bearer <cubemail token>` → `GET https://realiax.org/api/cubemail/wallet/me` → account =
first non-empty of `handle` / `username` / `email`, local part, trimmed, lower-cased; fallback to the
token's `handle`/`sub` claim (exactly like the registrar). Non-200 → 401; Cubemail unreachable → 502.
Valid tokens are cached by SHA-256 for `cube.token-cache-seconds` (300).

On first use an Alovoa user `<handle>@cubemail.io` is created (random BCrypt password, confirmed, no
e-mail notifications) with a `cube_account` row. The Cubeline comes from `GET http://127.0.0.1/cubeline/me`
with the same bearer; a missing Cubeline is retried at most every 2 minutes, a known one re-checked
daily. The Alovoa admin account can never be reached through a bearer.

Alovoa stores `firstName` with `updatable=false` (fixed at registration); Cube users get their name at
onboarding and may edit it, so the cube code writes it with one SQL update (encrypted with Alovoa's
`TextEncryptorConverter`, same as the entity).

## Privacy rules

- Locations are rounded to 2 decimals (~1 km) before storage; distances are whole km, `0` = under 1 km.
- Locations older than 30 days drop out of discovery (filtered at query time and cleared hourly).
- Discovery cards contain only uuid, name, age, distanceKm, photo URLs, description, interests,
  verified, intention. Never email, handle, Cubeline or coordinates.
- A Cubeline is returned only (a) to its owner in `GET /me` and (b) to the other person of a mutual,
  unblocked match (`POST /like` result and `GET /matches`).
- Photos are served only with a bearer: own photos always; others' only to onboarded viewers, for an
  active Cube profile, with no block in either direction.
- 18+ only: Alovoa's DOB age check (`Tools.calcUserAge`) at onboarding with a minimum of 18 whatever
  `app.age.min` is; preferred age range can't go below 18. Production also sets `app.age.min=18`.

## Opt-in visibility (Settings: Off / Browse privately / See and be seen)

`cube_account.visible` (boolean, **default false** for every account, including all new ones). The
app maps its three modes onto this flag (Off and Browse privately = `false` on the server; Off just
means the app doesn't open Explore). Server rules:

- Hidden users (`visible=false`) never appear in anyone's `GET /discover` and can't be liked by others (404).
- **Browse privately**: an onboarded hidden user may call `GET /discover` and gets normal results; they
  still never appear to anyone else. Discovery records nothing about who was viewed (Alovoa has no
  "viewed you" tracking; no likes or notifications are created).
- `POST /like/{uuid}` by a hidden user → `403 {"error":"Turn on \"See and be seen\" in Settings to like people","code":"not_visible"}`.
- `POST /pass/{uuid}` is allowed while hidden and only affects the caller's own deck (an Alovoa
  `UserHide` from the caller). Unlike upstream `UserService.hideUser`, a Cube pass never auto-blocks a
  person who had liked you, so it leaves no trace on their side.
- `GET /matches` (with the Cubelines of existing matches), unmatch, profile editing, photos, onboarding,
  block and report keep working while hidden.
- `GET /me` returns top-level `"visible"`. Toggle with `PUT /visibility {"visible": bool}` or
  `PUT /profile {"visible": bool}`. Turning it on before onboarding → `409 {"error":"Set up your dating profile first","code":"not_onboarded"}`.
  Endpoints that need a finished profile return `403 {"error":..., "code":"not_onboarded"}`.

## API (base `/api/v1/cube`, public at `http://109.122.27.74/dating/api/v1/cube`)

All endpoints need the bearer; errors are 4xx/5xx `{"error": "user-facing message"}` (some add `"code"`).

| Method | Path | Notes |
|---|---|---|
| GET | `/me` | `{onboarded, visible, cubeline, profile:{name, age, gender, preferredGenders[], intention, minAge, maxAge, description, interests[], photos[{id,url}], verified}}` |
| GET | `/options` | `{genders[{id,name}], intentions[{id,name}], interestsSuggestions[]}` |
| POST | `/onboarding` | multipart `photo` + `data` (JSON part or form field) `{name, dob, gender, preferredGenders[], intention, description, interests[]}`; returns `/me` |
| PUT | `/profile` | partial update of the above + `minAge`, `maxAge`, `visible`; dob can't change; returns `/me` |
| PUT | `/visibility` | `{visible}`; returns `/me` |
| POST | `/photos` | multipart `photo` → `{id, url}` (max 6) |
| DELETE | `/photos/{id}` | deleting the profile picture promotes the next photo; the last photo can't be deleted |
| POST | `/location` | `{lat, lon}` → `{ok:true}` |
| GET | `/discover?distanceKm=1..200&limit=1..30` | defaults 50 km / 20; out-of-range values are clamped; works while hidden (browse privately) |
| POST | `/like/{uuid}` | `{match, match_info?:{uuid, name, photo, cubeline}}`; 403 `not_visible` while hidden |
| POST | `/pass/{uuid}` | `{ok:true}`; own deck only, allowed while hidden |
| GET | `/matches` | `{matches[{uuid, name, age, photo, cubeline, matchedAt}]}` (ISO-8601, newest first) |
| DELETE | `/matches/{uuid}` | removes both likes, hides both ways |
| POST | `/block/{uuid}` | `{ok:true}` |
| POST | `/report/{uuid}` | `{reason}` (1–255 chars) → `{ok:true}`; also hides the person |
| GET | `/photo/{id}` | image bytes (`image/jpeg`), `Cache-Control: private, max-age=3600` |
| DELETE | `/me` | deletes the dating profile and its data |

`gender`, `preferredGenders`, `intention` are ids from `/options` (Alovoa's gender and intention
tables). Interests follow Alovoa's rules: 3–30 chars of `[a-zA-Z0-9-]`, max 10, stored lower-case.
Alovoa's intention change cooldown (once per 24 h after onboarding) applies to `PUT /profile`.
Onboarding requires a Cubeline (409 otherwise: "Your Cubeline isn't set up yet...").

## Tests

`mvn -q package` runs upstream's tests and the cube tests against MariaDB at `localhost:3306`
(user/password `alovoa_test`, as upstream's `application-test.properties` expects). Without sudo a
portable MariaDB bintar works (`mariadb-install-db --no-defaults --datadir=...` then `mariadbd
--no-defaults --port=3306 --bind-address=127.0.0.1 --socket=<short path>`; on Ubuntu 26.04 symlink
`libaio.so.1t64` as `libaio.so.1` into `LD_LIBRARY_PATH`).

- `CubeIdentityClientTest`: Cubemail/registrar against a local HTTP fake: field precedence, claim
  fallback, rejection, caching/expiry, 502 when unreachable, Cubeline parsing.
- `CubeGeoTest`: rounding, ranges, whole-km display.
- `CubeImageNormalizerTest`: formats, size and content checks.
- `CubeApiIntegrationTest` (Spring Boot + MockMvc + MariaDB + fake Cubemail/registrar): bearer filter
  and auto-provisioning (stateless, no cookies), 18+ enforcement, onboarding validation, profile and
  photos, location rounding, discover exclusions (no location, not onboarded, blocked/blocking, liked,
  passed, gender and age preferences both ways, stale location, outside radius), like → match →
  Cubeline only for mutual matches, unmatch/report/delete, opt-in visibility (default hidden, hidden
  users can discover but aren't discoverable or likeable, hidden like → 403, pass allowed and
  traceless, toggling via both endpoints, 409 before onboarding).
