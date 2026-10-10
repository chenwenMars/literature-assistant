# Repository Guidelines

## Project Structure & Module Organization
- Backend: Java 21 / Spring Boot 3.5.5 under `src/main/java/com/yuyuan/literature/`. Keep HTTP handling in `controller/`, business logic in `service/`, persistence in `mapper/`, and shared infrastructure in `common/`.
- Resources: `src/main/resources/` contains `application.yml`, schema `db.sql`, MyBatis XML in `mapper/`, and AI templates in `prompts/`.
- Tests: `src/test/java/` mirrors backend packages.
- Frontend: `literature-assistant-frontend/src/` contains Vue `views/`, reusable `components/`, Pinia `stores/`, `router/`, and shared styles in `style.css`.

## Build, Test, and Development Commands
Use Java 21, Maven, and Node.js 20+.

From the repository root:
- `mvn clean compile`: compile backend sources.
- `mvn spring-boot:run`: start the backend at `http://localhost:8086/api`.
- `mvn test`: run backend tests.
- `mvn clean package`: test and build the executable JAR in `target/`.

From `literature-assistant-frontend/`:
- `npm ci`: install locked dependencies.
- `npm run dev`: start Vite on port 3000; `/api` proxies to port 8086.
- `npm run build`: build production assets in `dist/`.
- `npm run preview`: preview the production build.

## Coding Style & Naming Conventions
Use four-space Java indentation and two-space Vue/JavaScript indentation. Follow existing JavaScript single quotes and omitted semicolons. Use PascalCase for Java classes and Vue components, camelCase for methods and variables, and descriptive suffixes such as `Controller`, `Service`, and `Mapper`. Prefer Vue Composition API with `<script setup>`. Reuse existing utilities, exception types, and persistence patterns. No formatter or lint script is configured.

## Testing Guidelines
Backend tests use JUnit 5 through Spring Boot Test; currently only a context-load test exists. Name test classes `*Tests` or `*Test`. Add focused regression tests for changed behavior, including failure paths. No coverage threshold or frontend test runner is configured. For frontend changes, build and manually verify affected upload, search, detail, or SSE flows; report verification performed.

## Commit & Pull Request Guidelines
History mixes short Chinese descriptions, English summaries, and `docs(guidelines): ...`; no uniform convention is enforced. Use concise, scoped commits. PRs should describe the problem, changes, validation, linked issues when applicable, and screenshots for UI changes.

## Configuration & Agent Guidelines
Keep API keys, logs, H2 `data/`, and uploaded documents out of commits. Verify APIs against existing code or official documentation; clarify ambiguous business rules. Fix root causes with small, verified changes. Validate at boundaries, keep core logic flat, and preserve architecture. Never conceal errors with silent fallbacks or fabricated success. Explicitly authorized fallbacks require warning/error logs, documentation, and a configuration switch.
