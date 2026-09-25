# ADR 0001: Parallel source calls with virtual threads, not WebFlux

**Status:** Accepted

## Context
The brief requires parallel requests to the Sales and Service systems. The total latency of a
search should be about the slowest source, capped by a timeout, not the sum of both.

## Decision
Use Spring MVC with blocking `RestClient` calls, each submitted as a `CompletableFuture` to a
`SimpleAsyncTaskExecutor` running **virtual threads**, with a per-source `orTimeout` deadline. The
executor has a concurrency limit and a task decorator that copies the tracing context and MDC.

## Alternatives
- **WebFlux / Reactor (`Mono.zip`)**: non-blocking and efficient, but the whole stack becomes
  reactive (including JDBC, which would need R2DBC), and stack traces and `ThreadLocal`-based
  context become harder to work with. Too much complexity for a two-call fan-out.
- **Platform-thread pool**: works, but it has to be sized, and a slow source can exhaust it.

## Consequences
- Code reads top to bottom and is easy to debug and test; parallelism is proven by a unit test.
- `orTimeout` stops *waiting* but cannot cancel a blocking call; the HTTP read timeout (same
  value) releases the thread. Both must stay aligned.
- Virtual threads make threads cheap, not source systems: the concurrency limit is what
  protects them under load.
