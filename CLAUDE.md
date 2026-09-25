# CLAUDE.md

Guidance for Claude Code in this repository. Keep this file short and current.

## Context
Keyloop Senior Software Engineer assessment — **Scenario D: The Unified Document Viewer**
(domain: Operate). I chose to implement the **backend**; the client layer is stubbed.
Evaluated on: system design, technical execution & tests, **how AI output is directed and
verified**, and communication. Code quality and verification evidence matter more than volume.

## Requirements (from the brief — do not change)
1. **Unified Search** — a single search interface where a user enters a VIN.
2. **Data Aggregation** — the backend makes **parallel** requests to two mocked external APIs:
   a "Sales System API" and a "Service System API".
3. **Aggregated View** — a single consolidated list of documents from both sources, clearly
   indicating the source system of each document.
4. Backend option: expose a **RESTful API** and use a **persistent database**. Stub the client
   with an OpenAPI spec, cURL examples, and a minimal test harness page.
5. Consider scalability, performance, reliability, maintainability, and observability.

## Design decisions & assumptions (mine — documented in docs/SYSTEM_DESIGN.md)
- Endpoint: `GET /api/v1/vehicles/{vin}/documents`; each document has `source` = SALES | SERVICE.
- Partial failure: if one source fails/times out, return the other's documents with
  per-source status (`partial: true`) instead of failing the whole request.
- Persistence: last known documents per VIN and source (`document_snapshot`, served flagged
  `stale` when that source fails) and one `search_audit` row per search. See ADR 0003.
- No source answered and nothing stored → 503 problem response, never an empty 200.
- Mocked external APIs run as WireMock services (docker compose) so calls are real HTTP.
  When a requirement is ambiguous: do not silently decide. Propose an assumption, wait for my
  approval, and record it under "Assumptions" in docs/SYSTEM_DESIGN.md.

## Stack
Java 25 · Spring Boot 4.1 (split starters, e.g. `spring-boot-starter-webmvc`) · Maven wrapper ·
PostgreSQL + Spring Data JPA + Flyway · Bean Validation · Actuator + Prometheus ·
Micrometer Tracing · WireMock · JUnit 5 + AssertJ + Testcontainers.

## Architecture rules
- Packages under `com.keyloop.docviewer`: `api`, `aggregation` (core logic), `domain` (model +
  ports, no Spring), `source.sales` / `source.service` (adapters), `persistence`, `observability`, `config`.
- One adapter (client + mapper) per external system, translating to the domain `Document`
  model. Domain code never sees external DTOs. A new source = a new adapter only.
- Parallel I/O on virtual threads. Every external call has an explicit timeout; never
  `.join()`/`.get()` without one.
- Source URLs, timeouts, retries via `@ConfigurationProperties` — never hard-coded.

## Scalability & performance
- Service is stateless (horizontally scalable); no in-memory state that breaks with >1 instance.
- Bound outbound concurrency and connection pools; no unbounded executors or queues.
- Index DB lookups by VIN; avoid N+1 queries; batch writes where applicable.

## Coding standards
- Java records for DTOs/value objects. Constructor injection. No Lombok.
- Validate at the edge (VIN: 17 chars, no I/O/Q, upper-cased) → 400.
- Errors as RFC 7807 `ProblemDetail`; never leak stack traces or upstream error bodies.
- Schema changes only via new Flyway migrations; never edit an applied migration.
- No secrets in the repo. No new dependencies without asking me first and stating why.

## Testing (TDD)
- Write the failing test first, show it failing, then implement.
- Unit tests for core business logic: VIN validation, merging, source tagging, sorting,
  partial-failure rules.
- Adapter tests against WireMock: success, empty, 404, 500, slow response/timeout.
- Integration tests with Testcontainers PostgreSQL (`@Import(TestcontainersConfiguration.class)`).
- A test must prove the calls run in parallel (e.g. two 1s delays complete in < 1.5s).
- NEVER disable, skip, or weaken a test, or raise a timeout, to make a build pass.

## Debugging
- Reproduce first, state the root-cause hypothesis, then fix. Add a regression test.
- No symptom patches (swallowing exceptions, retries/timeouts to hide failures) without my approval.

## Observability
- Structured JSON logs (Spring Boot built-in); no `System.out`.
- Correlation ID per request in the MDC, propagated to outbound calls and across threads.
- Per-source metrics: latency timer + outcome (`ok|timeout|error`) tagged by `source`.
- Traces show both outbound calls as parallel child spans. Health indicator per source.

## Workflow
- Non-trivial work: plan first, wait for my approval, implement in small steps.
- After each step: run `./mvnw verify`, summarize what changed and why, list risks/trade-offs.
- Do not commit or push — I review diffs and commit myself.
- Significant decisions → ADR in `docs/adr/NNNN-title.md`.
- After each task append to `docs/AI_LOG.md`: intent · AI proposal · what I changed/rejected ·
  how it was verified.

## Definition of done
`./mvnw verify` passes (locally and in CI) · behaviour covered by tests · OpenAPI and cURL
examples current · README and design doc updated if behaviour or setup changed.

## Deliverables (keep in sync)
- `docs/SYSTEM_DESIGN.md` — architecture diagram, component roles, data flow, technology
  choices with justifications, observability strategy, assumptions, **GenAI in design** section.
- `README.md` — build, run, test instructions; cURL examples; **AI Collaboration Narrative**
  (strategy for guiding AI, verification/refinement process, how final quality was ensured).
- `docs/AI_LOG.md` — raw log feeding the narrative. `.github/workflows/ci.yml` — runs `./mvnw verify`.

## Commands
Windows: `.\mvnw.cmd` (PowerShell) or `./mvnw` (Git Bash). Docker must be running.
```bash
./mvnw verify                          # full build + tests (definition of done)
./mvnw test -Dtest=ClassName#method    # single test
./mvnw spring-boot:run                 # run app; auto-starts compose.yaml services
./mvnw spring-boot:test-run            # run app with Testcontainers Postgres
docker compose up -d                   # Postgres + WireMock sales/service mocks
```

## Local infrastructure
- Runtime: `spring-boot-docker-compose` starts `compose.yaml` services and injects connection
  details — no datasource properties needed.
- Tests: `TestcontainersConfiguration` provides a `@ServiceConnection` PostgreSQL container.