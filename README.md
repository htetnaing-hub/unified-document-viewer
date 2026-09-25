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

The brief asks candidates to use AI as an essential collaborator and to show how its work is
directed and verified. This is how I did that.

**Strategy: requirements first, rules before code.**
- I started by giving Claude the assessment brief and the job description. I asked it to extract
  every requirement, deliverable and evaluation criterion before choosing anything. From that I
  chose the backend option and a stack I can defend: Java 25, Spring Boot 4.1, PostgreSQL and
  WireMock.
- Before any feature code, I wrote down the rules the AI must follow in [`CLAUDE.md`](CLAUDE.md):
  - the brief's requirements, quoted separately from my own assumptions
  - test-driven development, and never weakening a test to make it pass
  - an explicit timeout on every external call
  - a definition of done: `./mvnw verify` passes
- Shared permissions in `.claude/settings.json` let the assistant build and test freely, but
  commits need approval and pushes are blocked.
- I then directed Claude Code to implement the solution against those rules, which the brief
  explicitly allows. The work went in layers: domain, aggregation, adapters, persistence, API,
  observability and docs.

**Verification: nothing accepted on the AI's word.**
- **I checked its guidance against the source.** I asked whether its `CLAUDE.md` really matched the
  brief. The line-by-line check showed it had presented its own design decisions (endpoint path,
  partial-failure behaviour) as Keyloop's requirements. It also missed the cURL deliverable,
  scalability, debugging discipline and CI. All were fixed.
- **Setup problems were diagnosed, not worked around.** A `release version 25 not supported` build
  error was traced to `JAVA_HOME` pointing at JDK 19. The fix was the environment, not the project.
- **Failing tests were root-caused from logs.** When 2 of 54 tests failed:
  - One came from a cold in-process mock server, fixed with a warm-up request, not a longer timeout.
  - The other came from Spring Boot disabling the metrics endpoint in tests, fixed by enabling it
    explicitly.
- **The most important tests were mutation-checked.** Making the source calls sequential fails the
  parallelism test. Removing tracing-context propagation to the worker threads fails the tracing
  test. Both tests therefore prove what they claim.
- **Library APIs were checked against the real jars,** because Spring Boot 4 moved packages and the
  AI's memory of older versions is not reliable.
- **The running application was exercised end to end** for every scenario, with curl and in the
  browser. That check found a real port conflict and a demo-page styling bug.

**Where the process fell short.**
- `CLAUDE.md` asks for test-first development, but the first implementation was generated as one
  pass, with each layer's tests written right after its code rather than before it.
- I compensated with mutation checks on the key tests and by root-causing every failure instead of
  adjusting assertions. The one test added later (tracing) was written first and shown to fail
  against broken code.
- For a real team I would enforce test-first more strictly, one small step per prompt.

**Quality: how the final result is ensured.**
- **55 automated tests** cover the core business rules, both adapters and the full stack against real
  PostgreSQL.
- **CI** runs the same `./mvnw verify` on every push.
- **Every AI mistake that was caught is recorded** with its fix in [docs/AI_LOG.md](docs/AI_LOG.md).
  That includes one where the assistant drafted this kind of narrative describing review steps
  that had not happened yet. I required that the narrative contain only verified facts.

**Ownership.** The AI wrote most of the code, but the decisions are mine and I can explain each one:
- parallel calls on virtual threads rather than WebFlux (ADR 0001)
- partial results with a stale fallback rather than failing the search (ADR 0002)
- what is persisted and why (ADR 0003)
- a 503 instead of a misleading empty list when nothing is available

The known limits are documented rather than hidden in section 5 of the design doc: no circuit
breaker yet, no trace exporter, and no snapshot retention. With more time I would add those
first. I would also start the next project with the mutation check built into the workflow, since
it was the single most convincing way to prove a test is worth having.
