# IOU

IOU is a shared-bill application for groups of roommates, friends, and travel companions. Each person has an account, joins groups with a group code, and can create bills whose costs are split using member weights. The bill owner confirms payments; the app calculates outstanding amounts from unpaid shares.

The browser client is the only supported client. The Java desktop-client prototype has been removed because the project requirements specify a responsive web client.

## Technology

- **Backend:** Java 21, built with Gradle
- **Frontend:** TypeScript, HTML, CSS, and Vite
- **Database:** PostgreSQL through JDBC (Neon is a suitable hosted PostgreSQL provider)
- **Deployment:** Docker; the supplied Render configuration expects an external PostgreSQL database

Money is stored as integer cents and weights as integer hundredths. The server validates all requests independently of the browser.

## Project layout

```text
.
├── src/main/java/splitter/
│   ├── server/             # HTTP server, controllers, domain models, repositories
│   │   └── persistence/    # PostgreSQL connection and repositories
│   └── shared/             # Request/response models and validation
├── src/main/resources/
│   └── db/schema.sql       # PostgreSQL schema, created automatically at startup
├── src/test/java/          # Backend unit and requirement tests
├── frontend/
│   └── src/                # Browser application, API client, formatting, styles
├── docs/
│   ├── requirements.md
│   ├── architecture.md
│   └── acceptance-matrix.md
├── Dockerfile
└── render.yaml
```

## Run locally

You need **Java 21**, **Gradle 8.14 or later**, and **Node.js 20 or later**. If the `gradle` command is not recognized, install Gradle using the [official installation instructions](https://gradle.org/install/) and restart your VS Code terminal.

1. Start the Java backend from the repository root:

   ```bash
   gradle run
   ```

   By default the server listens on `http://localhost:8080` and uses local file storage in `data/`. This fallback is intended for development and quick testing only.

2. In a second terminal, start the browser client:

   ```bash
   cd frontend
   npm ci
   npm run dev
   ```

3. Open the local Vite address printed by the command (normally `http://localhost:5173`). Vite proxies `/api` requests to the Java server on port 8080.

To build the browser assets for the Java server:

```bash
cd frontend
npm ci
npm run build
cd ..
gradle run
```

The Vite build writes production files to `src/main/resources/web/`. That folder is generated and intentionally excluded from version control. The Java server serves those files when they exist.

## Use PostgreSQL / Neon

The deployed server should use PostgreSQL rather than local file storage. Neon is one option; any PostgreSQL provider can be used.

1. Create a PostgreSQL project in Neon.
2. Copy its connection string. It should begin with `postgresql://` (or `postgres://`). Keep it private.
3. Set the following environment variables for the Java server:

   | Variable | Value |
   |---|---|
   | `DATABASE_URL` | Your PostgreSQL connection string from Neon |
   | `IOU_REQUIRE_DATABASE` | `true` in hosted environments |
   | `PORT` | Supplied automatically by most hosts; defaults to `8080` locally |

4. Start the server. On startup it connects to PostgreSQL and creates the tables and indexes from `src/main/resources/db/schema.sql` if they do not already exist.

For a local PowerShell session, for example:

```powershell
$env:DATABASE_URL = "postgresql://USER:PASSWORD@HOST/DATABASE?sslmode=require"
$env:IOU_REQUIRE_DATABASE = "true"
gradle run
```

Replace the example connection string with the exact one from your provider; do not commit it to Git. On macOS/Linux use `export DATABASE_URL='...'` and `export IOU_REQUIRE_DATABASE=true` instead.

### Deploy to Render with Neon

1. Create the Neon database and copy its connection string.
2. Connect this repository to Render as a Docker web service. Render reads the supplied `render.yaml`.
3. In the Render service's Environment settings, set `DATABASE_URL` to the Neon connection string. `IOU_REQUIRE_DATABASE=true` is already configured in `render.yaml`.
4. Deploy. Render's health check calls `/api/health`; this endpoint checks the database connection as well as the server process.

The application does not provision a Neon database automatically. You create the database and provide its connection string through the host's secret environment-variable settings. The database connection is provider-neutral, so the same app can use another PostgreSQL host later.

**Important:** When `IOU_REQUIRE_DATABASE=true`, the server refuses to start without `DATABASE_URL`. This prevents a hosted deployment from silently saving user data to an ephemeral local file. If you are moving an already-used deployment from the old file-backed version, back up and migrate its `data/users.db` and `data/groups.db` contents separately before switching. This project does not automatically import those legacy files into PostgreSQL.

## Tests

Run backend tests from the repository root:

```bash
gradle test
```

Build the browser client separately:

```bash
cd frontend
npm ci
npm run build
```

The Java tests cover core user, group, bill, payment, persistence, and calculation rules. A live PostgreSQL integration run and full two-browser end-to-end run still need to be performed against a configured test database and browser environment; passing unit tests alone does not verify those deployment behaviours.

## Operational notes

- Login tokens are random bearer secrets. The server stores their SHA-256 hashes, not the original tokens.
- The browser stores the current username, login token, server address, and saved group codes locally. Do not use a shared/public browser for a personal account.
- `data/` is only for local file-backed development. Hosted deployments must set `IOU_REQUIRE_DATABASE=true` and use PostgreSQL.
- Do not add `.env` files, database URLs, passwords, or tokens to the repository.
