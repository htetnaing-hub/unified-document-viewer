# Mocked source systems

Two WireMock servers stand in for the dealership systems. The same mapping files are used
by `docker compose` (demo) and by the WireMock-based tests, so the demo and the tests exercise
identical data.

| System | Port | Endpoint | Unknown VIN |
|---|---|---|---|
| Sales System API | 8081 | `GET /api/v1/vehicles/{vin}/deals` | **404** |
| Service System API | 8082 | `GET /api/v1/vehicles/{vin}/repair-orders` | **200** with an empty list |

The two APIs deliberately differ, as two vendors' systems would: different field names
(`docId` vs `attachmentId`), nesting (`deals[].documents[]` vs `repairOrders[].attachments[]`),
date precision (`createdOn` date vs `timestamp` instant) and not-found conventions. The
adapters in `source/sales` and `source/service` absorb these differences.

## Scenario VINs

| VIN | Sales System | Service System | Demonstrates |
|---|---|---|---|
| `1HGCM82633A004352` | 200, 3 documents (150 ms) | 200, 3 documents (250 ms) | Happy path: merged, sorted, tagged by source |
| `5YJSA1E26HF000001` | 200, 2 documents | **5 s delay** | Timeout → partial result |
| `WBA3A5C51DF000002` | 200, 1 document | **500** (with internal details in the body) | Error → partial result; upstream body not leaked |
| `JH4KA7561PC000003` | **404** | 200, empty | Vehicle with no documents anywhere |
| `2T1BURHE0JC000004` | **connection reset** | 200, 1 document | Network fault → partial result |
| any other valid VIN | 404 | 200, empty | Empty result |

## Check them directly

```bash
curl.exe -s http://localhost:8081/api/v1/vehicles/1HGCM82633A004352/deals
curl.exe -s -w "\n%{http_code} in %{time_total}s\n" http://localhost:8082/api/v1/vehicles/5YJSA1E26HF000001/repair-orders
```
