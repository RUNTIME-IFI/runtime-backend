# Runtime backend

Ktor backend for the Runtime website's Strava integration.

## What it does

- handles Strava OAuth signup for members
- stores signed-up athletes and refresh tokens in a local JSON store
- exposes per-user stats endpoints for the existing `STRAVA` page
- exposes leaderboard endpoints for `run`, `ride`, and `swim` with `ytd`, `30d`, and `7d` periods
- keeps the old club helper endpoints available under both `/strava/*` and `/api/strava/*`

## Requirements

- Java 21+
- Gradle 9+
- a Strava app with OAuth callback configured

## Environment variables

Copy the template and fill in the real values:

```bash
cp .env.example .env.local
```

Required:

- `FRONTEND_BASE_URL` - frontend origin used for OAuth redirects, for example `http://localhost:5173`
- `CORS_ALLOWED_ORIGINS` - comma-separated list of allowed browser origins
- `STRAVA_CLIENT_ID` - Strava app client id
- `STRAVA_CLIENT_SECRET` - Strava app client secret
- `STRAVA_COOKIE_SIGNING_SECRET` - dedicated secret used only for signing auth cookies
- `STRAVA_REDIRECT_URI` - backend callback URL registered in Strava, for example `http://localhost:8080/strava/callback`

Optional:

- `STRAVA_CLUB_ID` - enables `/strava/info`, `/strava/members`, `/strava/admins`
- `STRAVA_SIGNUP_STORE_PATH` - path to the JSON signup store, defaults to `data/strava-signups.json`
- `STRAVA_AUTH_COOKIE_NAME` - auth cookie name, defaults to `runtime_strava_auth`
- `STRAVA_AUTH_COOKIE_SECURE` - set `false` for local `http`, `true` for deployed `https`

## Run locally

```bash
gradle run
```

The server listens on `http://localhost:8080` by default.

## OAuth flow

1. Frontend sends the user to `GET /strava/authorize?page=leaderboard` or `GET /strava/authorize?page=strava`
2. Backend redirects to Strava OAuth
3. Strava calls `GET /strava/callback`
4. Backend exchanges the code for tokens, stores the athlete, sets a signed auth cookie, and redirects back to the frontend with `?page=...&strava=success`

The signup store is file-backed on purpose so the feature works without adding a database to this repo. The path is configurable through `STRAVA_SIGNUP_STORE_PATH`.

## API

All routes exist under both `/strava` and `/api/strava`.

### Auth

- `GET /strava/authorize?page=leaderboard|strava`
- `GET /strava/callback`

### Stats for the signed-in athlete

- `GET /strava/stats/ytd`
- `GET /strava/stats/activities`
- `GET /strava/stats/monthly`

These endpoints require the signed auth cookie set during OAuth callback.

### Leaderboard

- `GET /strava/stats/leaderboard?activity=run&period=ytd`
- `GET /strava/stats/leaderboard?activity=ride&period=30d`
- `GET /strava/stats/leaderboard?activity=swim&period=7d`

Supported values:

- `activity`: `run`, `ride`, `swim`
- `period`: `ytd`, `30d`, `7d`

Response shape:

```json
{
  "type": "leaderboard",
  "data": {
    "activity": "run",
    "period": "ytd",
    "total_athletes": 3,
    "entries": [
      {
        "rank": 1,
        "athlete_id": 123,
        "athlete_name": "Ada Lovelace",
        "avatar_url": "https://...",
        "city": "Oslo",
        "state": null,
        "country": "Norway",
        "metrics": {
          "primary_label": "distance_km",
          "primary_value": 314.159,
          "secondary_label": "pace_per_km_seconds",
          "secondary_value": 313.0,
          "tertiary_label": "elevation_m",
          "tertiary_value": 2100.0
        },
        "totals": {
          "count": 42,
          "distance": 314159.0,
          "moving_time": 98340,
          "elapsed_time": 100100,
          "elevation_gain": 2100.0,
          "achievement_count": 0
        }
      }
    ]
  },
  "fetched_at": "2026-03-23T12:00:00Z"
}
```

### Club helper routes

- `GET /strava/info`
- `GET /strava/members`
- `GET /strava/admins`

These require `STRAVA_CLUB_ID` and at least one signed-up athlete so the backend has a valid Strava token to use.

## Development notes

- tokens are refreshed automatically when they are close to expiry
- leaderboard ranking sorts by total distance, then moving time, then athlete name
- leaderboard periods are calculated from raw Strava activities: year-to-date, last 30 days, or last 7 days
- activity matching includes common Strava variants like `TrailRun`, `VirtualRide`, and `VirtualRun`
- cookie auth is signed with `STRAVA_COOKIE_SIGNING_SECRET`
- frontend calls must use `credentials: include`

## Security

- the signup store contains refresh tokens; keep the file outside source control, lock down file permissions, and use restrictive access controls
- for deployed environments, encrypt token storage at rest and keep the encryption key in a real secret manager
- set `STRAVA_AUTH_COOKIE_SECURE=true` in production so cookies are only sent over HTTPS
- keep `STRAVA_CLIENT_SECRET` and `STRAVA_COOKIE_SIGNING_SECRET` separate so a leak in one path does not compromise the other
- define retention/deletion routines for athlete data and refresh tokens before using this in production

## Verify

```bash
gradle build
```
