# Unfold

A mobile-first dating MVP built with React + TypeScript + Tailwind, Java 21 + Spring Boot, and PostgreSQL. Real accounts, real persistence, and mutual matches. No mocked API or browser-local dating data.

## Run locally

Install and start Docker Desktop, then from this folder:

```sh
sh scripts/setup.sh
docker compose up --build -d
```

Open **http://localhost:5173**. Initial builds download Java and Node dependencies and may take several minutes. Local secrets are generated in ignored `.env`; keep this file private. PostgreSQL is not exposed to the host. The web app and backend bind to loopback only.

```sh
docker compose logs -f backend
docker compose down       # retains the database volume
```

`docker compose down -v` deletes all local account/profile data. Only use it when intentionally resetting the demo database. When retaining an existing database volume, keep its original `DB_PASSWORD`.

## Try a mutual connection

```sh
python3 scripts/seed_demo.py
```

The initial local setup has already been seeded; its generated password is saved in the ignored `.demo-credentials` file.

This command creates fictional Alex, Maya, and Sam profiles and prints a random demo password. Save that password. To rerun the seed, set `DEMO_PASSWORD` to the same value. Sign in as `alex@unfold.example`, then like Maya: she has already liked Alex. Both accounts can see their mutual connection. All demo emails end in `.example`; portraits are illustrative Unsplash images, not identities of the people pictured. Demo data is never seeded automatically.

Alternatively, create two accounts yourself in separate browser sessions, complete both profiles with compatible preferences, then like each other.

## Implemented

- Responsive welcome page, login, three-step onboarding, discovery, profile/preferences editing, and connections.
- BCrypt passwords, signed two-hour JWTs, Spring Security, and validated API requests.
- JWT held in session storage; signing out clears the browser copy. No refresh-token or server-side revocation yet.
- Adults 18–100 only; date of birth remains private in discovery and matches.
- Reciprocal gender and age preference filtering. City is displayed; location-radius filtering is not implemented.
- Persistent pass/like decisions. A match exists only when both users like each other. Decisions are idempotent and final in this version.
- Concurrent likes serialize using ordered account locks. Match rows are derived from reciprocal likes rather than duplicated.
- Flyway migrations, PostgreSQL volume, non-root backend image, same-origin API proxy, basic IP rate limiting, and security headers.
- Web app manifest and icon. Offline behavior, service worker, and full installability testing are deferred.

## Verify

```sh
python3 scripts/smoke.py
```

Runs against the real stack: authentication, rejected invalid profiles, private field protection, discovery, simultaneous likes, idempotent decisions, and both users' matches. It creates unique fictional test accounts in your local database. Run against a disposable database for clean discovery results. `UNFOLD_URL` overrides the default URL.

The frontend Docker build runs TypeScript checks and a production Vite build. The backend Docker build runs Maven verify. Integration coverage is supplied by the smoke script; there are no Java unit tests yet.

## Layout and API

- `frontend/`: React app, Tailwind integration, Vite development proxy, production Nginx.
- `backend/`: Spring Security, JWT issuance, JDBC API, SQL migrations.
- `scripts/`: local secret setup, demo seeding, integration verification.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| POST | `/api/auth/register` | Email/password registration |
| POST | `/api/auth/login` | Return two-hour JWT |
| GET | `/api/me` | Account ID and private profile |
| PUT | `/api/me/profile` | Validate and save onboarding/preferences |
| GET | `/api/discover` | Up to 50 compatible, undecided profiles |
| POST | `/api/profiles/{id}/decision` | `{ "liked": true }` or `false` |
| GET | `/api/matches` | Mutual connections |
| GET | `/api/health` | Application and database health |

All endpoints except auth and health require `Authorization: Bearer <token>`. Registration requires a 10–72-character password (at most 72 UTF-8 bytes). The API returns useful validation errors without exposing stored password hashes.

For native development, install Node 22, Java 21, and Maven 3.9. Run `npm install && npm run dev` in `frontend`; Vite forwards `/api` to port 8080. Run `mvn spring-boot:run` in `backend` with `DB_URL`, `DB_USER`, `DB_PASSWORD`, and `JWT_SECRET` set for a reachable PostgreSQL instance. The Compose database is deliberately not published to the host.

## Next phases

Phase 3: matched-user WebSocket messaging, blocking and reporting, then optional WebRTC calling. Phase 4: experiment with personal introductions and meaningful conversation features. Phase 5: CI, expanded tests, email verification/password recovery, image uploads, moderation, accessibility review, production deployment, and submission packaging.

This is a local early-access MVP, not ready for public dating use. Before public release, implement blocking/reporting, moderation, account/data deletion, email verification and recovery, TLS, backups, stronger auth abuse protection, token revocation, and a privacy policy. The birth-date check is self-reported, not identity verification. Do not collect real sensitive information in a submission demo.

## Deployment boundaries

No hosting accounts or GitHub repository have been created. The React static build can be served by Vercel with a same-origin `/api` proxy to the backend; host the Java container on a JVM/container-compatible platform and use managed PostgreSQL. Preserve TLS and proxy settings, configure secrets, and add production monitoring before launch. Sites' Worker runtime does not run this Spring Boot service.

## Verification completed

- Docker builds: Maven verify and TypeScript/Vite production build passed.
- Live PostgreSQL integration: authentication, adult/age-range validation, private-field exclusion, concurrent mutual likes, decision idempotency, and match retrieval passed.
- Chrome: real demo login and discovery rendered successfully; welcome and discovery had no horizontal overflow at a 390px mobile viewport. Desktop checked at 1440px.
- Local health endpoint returned `{"status":"ok"}`. Test-created accounts were removed; fictional demo profiles remain.
- Screenshots: [welcome](docs/welcome-desktop.png), [discovery](docs/discovery-desktop.png), [mobile discovery](docs/discovery-mobile.png).

Browser checks did not cover every onboarding input, screen reader, or browser engine. Automated API checks are reproducible via `scripts/smoke.py`.
