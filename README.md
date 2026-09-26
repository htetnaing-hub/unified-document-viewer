# Unified Document Viewer

One search by VIN that returns every document about a vehicle from two dealership systems (a
**Sales System** and a **Service System**), queried **in parallel** and merged into one list that
shows where each document came from.

Keyloop technical assessment, Scenario D. The **backend** is implemented fully: REST API,
PostgreSQL persistence, parallel aggregation, observability and tests. The client layer is
stubbed with an OpenAPI contract, cURL examples, an IntelliJ HTTP file and a small HTML page.

- Design: [docs/SYSTEM_DESIGN.md](docs/SYSTEM_DESIGN.md) · Decisions: [docs/adr](docs/adr) · AI log: [docs/AI_LOG.md](docs/AI_LOG.md)

## What it does

```
GET /api/v1/vehicles/{vin}/documents
        │
        ├─► Sales System API   (deals → documents)          ┐ in parallel,
        └─► Service System API (repair orders → attachments) ┘ 2 s timeout each
        │
        ▼
one list, newest first, each document tagged SALES or SERVICE
+ a status per source (OK / TIMEOUT / ERROR) and partial=true if one failed
```

- If one system is slow or down, the other system's documents are still returned, and the failed
  system's **last known** documents are included and flagged `stale`.
- If no system answers and nothing is stored, the API returns **503** with a problem response,
  never a misleading empty list.
- Every search is audited (VIN, correlation id, outcome per source) in PostgreSQL.

## Prerequisites

- **JDK 25** (`JAVA_HOME` must point to it; `./mvnw -v` should report Java 25)
- **Docker** running (PostgreSQL and the two mock systems; Testcontainers in the tests)

## Build, run and test

```bash
./mvnw verify            # build + all tests (unit, WireMock adapter tests, Testcontainers integration tests)
./mvnw spring-boot:run   # run the app; starts PostgreSQL + both mock systems from compose.yaml
```

On Windows PowerShell use `.\mvnw.cmd` instead of `./mvnw`.

Then open **http://localhost:8080** for the demo page, or **http://localhost:8080/swagger-ui.html**
for the API contract.

If port 8080 is taken, choose another port, for example:

```bash
SERVER_PORT=8090 ./mvnw spring-boot:run
```

In PowerShell: `$env:SERVER_PORT=8090; .\mvnw.cmd spring-boot:run`.

The Docker services are started and stopped with the app. To run them on their own:
`docker compose up -d`.

## Try it

The mock systems include scenario VINs (details in [mocks/README.md](mocks/README.md)):

| VIN | Scenario | Expected |
|---|---|---|
| `1HGCM82633A004352` | Both systems answer | 6 documents, `partial: false` |
| `5YJSA1E26HF000001` | Service System takes 5 s | Sales documents after about 2 s, Service `TIMEOUT`, `partial: true` |
| `WBA3A5C51DF000002` | Service System returns 500 | Sales documents, Service `ERROR` |
| `2T1BURHE0JC000004` | Sales System resets the connection | Service documents, Sales `ERROR` |
| `JH4KA7561PC000003` | Vehicle unknown to both | Empty list, `partial: false` |

```bash
curl -s http://localhost:8080/api/v1/vehicles/1HGCM82633A004352/documents
curl -s http://localhost:8080/api/v1/vehicles/5YJSA1E26HF000001/documents
curl -s -i http://localhost:8080/api/v1/vehicles/NOT-A-VIN/documents          # 400 problem+json
curl -s -i -H "X-Correlation-Id: demo-1" http://localhost:8080/api/v1/vehicles/1HGCM82633A004352/documents
curl -s http://localhost:8080/actuator/health                                 # includes documentSources
curl -s http://localhost:8080/actuator/prometheus | grep docviewer_source     # per-source metrics
```

In PowerShell, use `curl.exe` (plain `curl` is an alias for `Invoke-WebRequest`). In IntelliJ,
open [http/documents.http](http/documents.http) and run the requests from the gutter.

Stale fallback: search `2T1BURHE0JC000004`, stop the Service mock (`docker compose stop
service-api`), and search again. The Service documents come back flagged `stale`.

## Tests

`./mvnw verify` runs 55 tests:

| Suite | Focus |
|---|---|
| `DocumentAggregatorTest` | Core business logic: calls run in parallel, a slow source times out without delaying the other, stale fallback, 503 rule, audit, metrics, MDC propagation, database failures do not fail a search |
| `AggregatedDocumentsTest`, `VinTest` | Merging, sort order, partial/stale rules, VIN validation and masking |
| `SalesSystemClientTest`, `ServiceSystemClientTest` | Each adapter against WireMock with the demo mapping files: mapping, 404, 500, connection reset, timeout, tolerant reading, no upstream body leakage, correlation id forwarding |
| `DocumentSearchTracingTest` | One search is one trace; both source calls are child spans of the request and overlap in time |
| `DocumentSearchIntegrationTest` | Full stack with PostgreSQL (Testcontainers) and both mocks: response contract, partial results within the timeout, stale fallback, 400/503 problems, audit, metrics, health, OpenAPI |

Two key tests were mutation-checked: making the calls sequential fails the parallelism test, and
removing tracing-context propagation to the worker threads fails the tracing test.

## Project structure

```
src/main/java/com/keyloop/docviewer
├── api            REST controller, response DTOs, problem responses
├── aggregation    DocumentAggregator: parallel fan-out, timeouts, fallback, audit
├── domain         Vin, Document, SourceResult, ports (DocumentSource, stores)
├── source         shared HTTP client setup and failure mapping
│   ├── sales      Sales System adapter (wire model + mapper + client)
│   └── service    Service System adapter
├── persistence    JPA snapshot store and search audit
├── observability  correlation id, context propagation, metrics, health
└── config         wiring and configuration properties
src/main/resources/db/migration   Flyway migrations
mocks/                            WireMock mappings for both systems (demo + tests)
docs/                             system design, ADRs, AI log
```

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `docviewer.sources.sales.base-url` | `http://localhost:8081` (env `SALES_API_URL`) | Sales System API |
| `docviewer.sources.service.base-url` | `http://localhost:8082` (env `SERVICE_API_URL`) | Service System API |
| `docviewer.sources.*.timeout` | `2s` | Maximum wait per source |
| `docviewer.sources.*.connect-timeout` | `500ms` | TCP connect timeout |
| `docviewer.aggregation.max-concurrent-source-calls` | `200` | Cap on in-flight source calls per instance |

## AI Collaboration Narrative

I treated the AI as a fast, capable collaborator whose output is **a proposal, not a fact**. I
owned three things it could not: the requirements, the rules it had to follow, and the
verification. Every claim below can be checked in the repository (see the last part of this section).

### 1. How I directed the AI

```
Brief ──► Rules ──► Plan ──► Generate ──► Verify ──► Log ──► Commit
          CLAUDE.md          (Claude Code)  tests, mutation,  AI_LOG.md
                                            run the real app
```

| Stage | Tool | What I did |
|---|---|---|
| Understand | Claude (chat) | Had it extract every requirement, deliverable and evaluation criterion from the brief and the job description **before** any design. Chose Scenario D, the backend option and the stack from that list. |
| Set guardrails | [`CLAUDE.md`](CLAUDE.md), [`.claude/settings.json`](.claude/settings.json) | Wrote down the rules: the brief's requirements quoted separately from my assumptions, timeouts on every external call, never weaken a test, and a definition of done (`./mvnw verify` passes). Permissions let the AI build and test freely, but commits need approval and pushes are blocked. |
| Diagnose | Claude Code in IntelliJ | Used it to root-cause environment problems (JDK 19 on `JAVA_HOME`, Docker not running) instead of patching the project around them. |
| Implement | Claude Code | Directed it to build the solution layer by layer against those rules, which the brief explicitly allows. |
| Verify | Tests, CI, the running app | Techniques in section 2. |

### 2. How I verified the output

| Technique | Why it matters | Evidence |
|---|---|---|
| **Mutation checks** on the key tests | A test that has never failed proves nothing. Making the calls sequential fails the parallelism test; removing context propagation fails the tracing test. | `callsSourcesInParallel`, `DocumentSearchTracingTest`; AI log rows 7, 12 |
| **Root cause before fix** | When 2 of 54 tests failed, the causes were a cold in-process mock server and Spring Boot disabling metrics in tests. Neither was fixed by raising a timeout or weakening an assertion. | AI log rows 5, 6 |
| **Checking the AI against the source** | The AI's knowledge of Spring Boot 4 lagged, so class locations were confirmed in the actual dependency jars. Its `CLAUDE.md` was checked line by line against the brief. | AI log rows 2, 3 |
| **Running the real system** | I ran every scenario myself in IntelliJ against Docker, including stopping the Service mock to watch the stale fallback. The first end-to-end run surfaced a port conflict and a UI bug that no test covered. | AI log rows 8, 9 |
| **CI as the final judge** | The same `./mvnw verify` runs on a clean machine on every push. | GitHub Actions: 55 tests passing |

### 3. Where the AI was wrong, and how it was caught

| AI output | How it was caught | Fix |
|---|---|---|
| `CLAUDE.md` listed the AI's own design choices as Keyloop's requirements, and missed four deliverables | I asked it to prove the file against the PDF | Split "requirements (from the brief)" from "assumptions (mine)"; added the missing rules |
| A draft of this narrative described review steps that had not happened | Every claim checked against the log and git history | Rewritten to contain only verified facts |
| The design doc claimed the source calls appear as parallel trace spans, but nothing tested it | Self-review against the evaluation framework | Added a tracing test and mutation-checked it |
| The architecture diagram used `{vin}` in an unquoted Mermaid label, so it rendered as raw code | **I spotted it** when viewing the doc on GitHub | Quoted the labels; verified with Mermaid 9, 10 and 11 |

### 4. Where my process fell short

`CLAUDE.md` asks for test-first development, but the first implementation was generated in one
pass, with each layer's tests written straight after its code. I compensated with mutation checks
and root-cause debugging, and the test added later (tracing) was written first and shown to fail.
Next time I would enforce test-first by giving the AI one small, test-led step per prompt.

### 5. Ownership, and what I would bring to a team

The AI wrote most of the code; the decisions are mine and I can defend each one:
- parallel calls on virtual threads rather than WebFlux (ADR 0001)
- partial results with a stale fallback rather than a failed search (ADR 0002)
- what is persisted and why (ADR 0003)
- a 503 rather than a misleading empty list when nothing is available

Known limits are documented, not hidden (design doc, section 5): no circuit breaker, no trace
exporter, and no snapshot retention yet.

The parts of this workflow that transfer to a team are practical:
- **A shared `CLAUDE.md`** that encodes the team's standards, so every engineer's AI follows them.
- **Guardrail permissions** that let the AI verify its own work but never push.
- **Mutation-checking** any AI-written test that guards critical behaviour.
- **A lightweight AI log**, so reviewers can see what was generated, what was changed and why.

### Check these claims yourself

```bash
git log --oneline      # the build-up, layer by layer
./mvnw verify          # 55 tests, including the mutation-checked ones
```

The full log of prompts, AI proposals, corrections and verification is in
[docs/AI_LOG.md](docs/AI_LOG.md).
