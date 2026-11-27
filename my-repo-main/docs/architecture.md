# IOU architecture

## Overview

IOU has one browser client and one Java HTTP server. The browser sends JSON requests to the server and renders the returned group snapshots. The server is authoritative: it authenticates requests, validates permissions and input, calculates bill shares, and persists changes.

In production the server uses PostgreSQL through JDBC. For local development only, it can fall back to the original file-backed repositories when `DATABASE_URL` is absent. Set `IOU_REQUIRE_DATABASE=true` on a hosted server to prevent this fallback.

## Request flow

```text
Browser (TypeScript)
    │  JSON over HTTP
    ▼
Java HTTP server (ServerMain)
    │
    ├── UserApiController
    ├── GroupApiController ── AuthGuard
    └── BillApiController  ── AuthGuard
               │
               ▼
       Group/User repositories
          ├── PostgreSQL repositories (hosted)
          └── File repositories (local development)
```

## Backend responsibilities

- `ServerMain`: registers HTTP endpoints and serves the built browser assets.
- `controller`: handles each API use case and performs current-state authorization and validation.
- `model`: represents users, groups, memberships, and performs group-level operations.
- `persistence`: creates PostgreSQL connections, initializes the schema, and implements PostgreSQL-backed repositories.
- `shared`: request/response data types, input validation, money formatting, and outstanding-balance calculations.

Group mutations are protected by a per-group in-process lock. PostgreSQL repository writes also lock the group row and run in a database transaction. This prevents requests handled by the current single server process from interleaving and makes each database write atomic. Keep the hosted deployment to one backend instance for this design; multi-instance deployment would need distributed locking or a transaction boundary that includes the authorization checks.

## Identity and history

A username identifies an account across the application. A `GroupMember` is one membership in one group and has a stable `memberOrder`, assigned when the member joins. That number is append-only and is also used as the membership identifier in this implementation.

Bills store the bill owner's membership identifier. Each bill share stores the membership identifier it belongs to. Names are presentation data only. This is important because a person may leave and later rejoin, and a former member's name may be reused without changing old bills.

## Database

The PostgreSQL schema is in `src/main/resources/db/schema.sql`. It is applied idempotently when the server starts. Important constraints include unique usernames, unique group codes, one current membership per user per group, unique current display names within a group, integer-cent money fields, integer-hundredth weight fields, and foreign keys between bills, memberships, and shares.

Each repository mutation opens a connection, begins a transaction, locks the group row when applicable, applies its updates, and commits before returning. An exception causes rollback.

## Browser responsibilities

- `api.ts`: HTTP requests, timeout handling, and connection notifications.
- `main.ts`: app state, navigation, event handlers, rendering, and five-second polling.
- `format.ts`: exact money/weight parsing, formatting, escaping, and derived balances.
- `types.ts`: browser-side API and model types.
- `style.css` and `index.html`: presentation and screen structure.

The browser polls group or home data every five seconds. The server remains the source of truth; browser-side checks are for user feedback and do not replace server-side validation.

## Deployment

The Dockerfile builds the Vite frontend, builds the Java application with Gradle (including the PostgreSQL JDBC driver), and runs the packaged application on Java 21. `render.yaml` configures a Docker web service and requires `DATABASE_URL` to be set in Render's environment settings. Neon is a recommended PostgreSQL provider, not a hard-coded dependency.
