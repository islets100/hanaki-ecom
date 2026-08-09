# Repository Guidelines

## Project Structure & Module Organization

The Next.js 16 frontend lives in `app/`; route UI starts in `app/page.tsx`, shared layout is in `app/layout.tsx`, and global styles are in `app/globals.css`. Static assets belong in `public/`. The Spring Boot 3.5 backend is under `server/`: production Java code follows packages beneath `src/main/java/com/hanaki/ecom`, configuration and seed SQL live in `src/main/resources`, and tests mirror those packages in `src/test/java`. `worker/` contains the Cloudflare worker entry point. Observability configuration is grouped in `observability/` and `docker-compose.observability.yml`. Treat `build/`, `.next/`, and `server/data/` as generated runtime output.

## Build, Test, and Development Commands

- `pnpm install` installs the Node 22+ dependencies from `pnpm-lock.yaml`.
- `pnpm dev` starts the frontend at `http://localhost:3000`.
- `pnpm lint` runs the Next.js ESLint rules; `pnpm build` performs the production build and TypeScript checks.
- `./server/mvnw test` (`server\mvnw.cmd test` on Windows) runs the Java 21/JUnit suite.
- `./server/mvnw spring-boot:run` starts the API at port 8080. On Windows, `./start-backend.ps1` also loads root `.env` values.
- `docker compose up --build` starts the containerized frontend and backend.

## Coding Style & Naming Conventions

Use two-space indentation and double quotes in TypeScript/TSX, following the existing files and `eslint.config.mjs`. Keep TypeScript strict and prefer the `@/*` path alias for root imports. React components and Java types use `PascalCase`; variables and methods use `camelCase`; Java packages remain lowercase. Keep backend classes in the closest domain package, such as `agent`, `rag`, or `commerce`.

## Testing Guidelines

Backend tests use JUnit 5 through `spring-boot-starter-test`. Name tests `*Test.java`, mirror the production package, and cover tenant isolation, authorization, idempotency, and failure paths for behavioral changes. The frontend has no unit-test runner; lint and a successful production build are required checks.

## Commit & Pull Request Guidelines

Use Conventional Commits with concise, imperative subjects (for example, `fix(agent): enforce tenant scope`). Keep each commit focused. Pull requests should explain the behavior change, list verification commands, link relevant issues, and include screenshots for UI changes or request/response examples for API changes.

## Security & Configuration

Copy `.env.example` to `.env`; never commit real API keys or secrets. Replace `CONFIRM_SECRET` and invite/provisioning codes outside local development. Do not place credentials in `application.yml`, source files, logs, or test fixtures.
