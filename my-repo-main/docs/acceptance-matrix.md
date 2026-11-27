# Acceptance and verification status

This file records what has actually been verified in the repository. A feature being implemented is not the same as being fully verified against every requirement.

| Area | Implementation location | Current verification |
|---|---|---|
| Username creation and token authentication | `UserApiController`, `AuthGuard`, `TokenGenerator`, `UserRepository`, `PostgresUserRepository` | Backend unit tests pass; live database test still required |
| Groups, group codes, membership, and ownership | `GroupApiController`, `Group`, `GroupMember`, repositories | Backend unit tests pass; two-user browser test still required |
| Bill creation and weighted share calculation | `BillApiController`, `SplitCalculator`, `Bill`, `BillShare` | Backend unit tests pass |
| Payment confirmation and cancellation | `BillApiController`, repositories | Backend unit tests pass |
| Outstanding amounts | `OutstandingCalculator`, frontend `computeBalances` | Backend unit tests pass; UI/browser check still required |
| PostgreSQL schema and persistence | `db/schema.sql`, `PostgresDatabase`, PostgreSQL repositories | Compiles; requires a real PostgreSQL/Neon integration run |
| HTTP API and server health check | `ServerMain` and API controllers | Local HTTP smoke passed: create users, create a group, join, create a bill, and confirm a share; PostgreSQL HTTP path still needs testing |
| Browser client | `frontend/src` | Strict TypeScript source check passed; Vite production bundling could not be verified because npm dependency installation timed out |
| Five-second refresh and offline behaviour | `frontend/src/main.ts`, `frontend/src/api.ts` | Implemented; two-browser end-to-end verification still required |
| Docker/Render deployment | `Dockerfile`, `render.yaml` | Configuration reviewed; image deployment not run here |

## Checks run for this cleaned project

- Java 21 compilation of the server and shared source: passed.
- Included Java test suite: 34 tests passed, 0 failed.
- Frontend application TypeScript source type-check with strict compiler flags: passed.
- Vite production bundling was not run because npm dependencies could not be installed; `npm ci` timed out while trying to reach the package registry.
- PostgreSQL integration: not run; no Neon credentials or local PostgreSQL server were available.
- Docker image build: not run; Docker was unavailable in the environment.

## Manual end-to-end scenarios still to run

1. Create two accounts and a group; join the second account using the group code.
2. Create a weighted bill and verify that both browsers see it after refresh.
3. Verify that only the bill owner can confirm another member's share.
4. Verify that paid shares and outstanding balances update in both browsers.
5. Have the bill owner leave; verify that ownership transfers to the earliest current member.
6. Rejoin the former member using a different display name; verify old bills still refer to the original membership.
7. Stop the server; verify the connection banner and disabled mutation controls, then restart it and verify recovery.
8. Restart the server with the same PostgreSQL database; verify that all users, groups, bills, shares, and payment states remain.
