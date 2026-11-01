# Download request sources

The service and client hooks are source-ready and **disabled by default**.
They have not been deployed or included in a published app. Keep existing public
GitHub links until the first-party HTTPS endpoint has been deployed and checked.

GitHub release asset download counts do not identify the caller. Windows and
Android updaters use the same assets that a person can download in a browser.
Historical/manual/updater attribution cannot be recovered from those counters;
its source is **unknown**. Do not subtract tracked requests from GitHub counts
to invent an unknown/manual remainder: those metrics cover different events.

## What is counted

- `manual`: a GET to a controlled `/d/<asset-id>` link, followed by a redirect
  to an exact administrator-pinned GitHub asset URL.
- `update`: an explicit best effort report immediately before an in-app GitHub
  download attempt. Includes initial engine installation and engine updates.
- Dimensions: `platform=windows|android`, `product=app|engine`.

These are **requests**, not completed downloads, installations, first-time users,
unique people, or conversions. Retries, link scanners and repeated clicks can
count repeatedly; failures and cancellation can count too. Events are public,
self-reported and spoofable. Rate limits reduce abuse, not establish identity.
Disabled/old clients and direct GitHub links remain outside source coverage.
There is no client shared secret: a secret shipped in an app would be public.

SQLite stores Moscow calendar-day aggregates only (730 days), with a stable
counter epoch UUID and collection start time. No IP address, user agent, device,
account or event-level record is persisted. The service disables request logging;
the proxy template disables access/error logs for this route. Check any external
CDN/hosting logs separately before describing the deployment as anonymous.

## API

External base after deployment: `https://vnenapravo7.ru/mcrf-downloads`.
Local service: `http://127.0.0.1:8787`; the proxy removes the external prefix.

`GET /d/<asset-id>` counts `manual` and returns 302. Only configured IDs work;
URL/query overrides and encoded path aliases are rejected. HEAD is not counted.
Counting failure still redirects to the real download.

`POST /v1/events`, `Content-Type: application/json`, maximum 2048 bytes:

```json
{"source":"update","platform":"windows","asset_url":"https://github.com/vnenapravo7-source/mcrf/releases/download/v1.1.2/mcrf-app-update.zip"}
```

The URL must exactly match the local manifest and its platform. Product is
determined by that manifest. Returns 202 when recorded, 400 for invalid input,
503 if storage is unavailable. This service never fetches an input URL.

`GET /v1/stats` requires `Authorization: Bearer <MCRF_SOURCE_STATS_TOKEN>`.
The secret is server/monitor-only and comes from the environment, never source.
Example response (counts omitted):

```json
{
  "schema": 1,
  "metric": "download_requests",
  "completed_downloads": false,
  "coverage": "controlled_links_and_opted_in_clients_only",
  "timezone": "Europe/Moscow",
  "retention_days": 730,
  "counter_epoch": "stable-uuid-per-database",
  "started_at": "2026-11-01T12:00:00+03:00",
  "generated_at": "2026-11-01T12:00:00+03:00",
  "totals": [{"source":"update","platform":"windows","product":"app","count":1}],
  "days": [{"day":"2026-11-01","source":"update","platform":"windows","product":"app","count":1}]
}
```

Totals cover retained days, not lifetime. An absent row is zero only within
collection coverage; a day before `started_at` is unobserved. Reset/replacement
of the database changes the epoch. Monitor request metrics must be separately
labelled from GitHub download counters. `GET /healthz` only checks process health.

## Deployment (operator steps; not performed by adding these files)

1. Install Python 3.10+ and nginx on the authorized host; create system user
   `mcrf-downloads`. Copy `maintenance/download_source_service.py` to
   `/opt/mcrf-downloads/` and the unit into `/etc/systemd/system/`.
2. Create `/etc/mcrf-downloads/` and an `assets.json`, initially based on
   `maintenance/deploy/download-source-assets.example.json`. **Examples are
   historical, not a current release inventory.** Pin only actual approved
   assets, with unique IDs/URLs and the correct platform/product. Add the full
   Windows installer separately for manual links and individual engine assets
   with `product: engine`. Only this repository's exact GitHub release URLs are
   accepted; there are no wildcard/remote manifests or arbitrary redirects.
3. Generate a random secret, e.g. `python3 -c 'import secrets; print(secrets.token_urlsafe(48))'`.
   Write `MCRF_SOURCE_STATS_TOKEN=<secret>` to `/etc/mcrf-downloads/service.env`,
   mode 0600 owned by root. Keep it out of shell history and repository files.
4. Install the nginx zones/location into the existing **valid TLS** host config;
   the template is a fragment, not a replacement virtual host. Keep port 8787
   private/loopback. Check `nginx -t`, then reload; enable/start the unit after
   `systemctl daemon-reload`. Never bind this stdlib service directly to Internet.
5. Check health, authenticated stats, one controlled test event and redirect,
   unknown-ID rejection, rate limiting, counter persistence after restart and
   privacy/log settings. These checks add requests; initialize a fresh production
   database after staging tests if production must start at zero. Back up SQLite
   with its backup API and protect the backup/secret.
6. Wire the monitor's optional source stats endpoint/token only after that check.
   Then opt in rebuilt clients and replace chosen manual download links with
   `/mcrf-downloads/d/<asset-id>`. Existing direct links keep working but are not
   attributed. No links were changed as part of this source change.

Manifest changes require restarting the service; pin new assets before activating
links/releases. Retain previous asset entries while older clients can request
them. Database retention is bounded by dimensions and days, not by asset count.

## Client activation

Windows: explicitly set `MCRF_DOWNLOAD_SOURCE_REPORTING=1` for the app process.
Default is off. A single background reporter sends to the fixed HTTPS endpoint,
with a 1.5 second request abort; failures are ignored. App/core/optional engine
download paths are covered only for configured first-party GitHub assets.
Third-party upstream assets are not reported. Requests while a report is in
flight are dropped (intentional undercount to bound resource use).

Android: build with `-PdownloadSourceReporting=true` to set
`BuildConfig.DOWNLOAD_SOURCE_REPORTING`; default is false. App and engine download
paths launch at most one daemon reporting worker, without a queue or retries.
Connect/read timeouts are 1.5 seconds; platform DNS resolution may exceed those,
but never holds up the actual download or UI. This is a distribution opt-in,
not a claim that an end-user consent setting exists.

Both clients continue downloading the original GitHub URL directly. Existing
HTTPS allowlists, size/hash checks, APK signatures and installation checks remain
the authority; reporting responses are never used as update metadata. Roll back
by disabling the Windows flag/building Android without the property. No app
build, installation, release publication or server deployment is implied here.

## Verification

`python -m unittest discover -s tests -p test_download_source_service.py`
exercises real local HTTP requests, exact redirect/asset allowlisting, auth,
payload limits, malformed events, atomic parallel counts, schema privacy,
retention, persistence and storage failures. Client compile/runtime checks must
be included in the next authorized grouped app verification/build; service tests
alone do not prove Android/Windows compilation or production connectivity.
