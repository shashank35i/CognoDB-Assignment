# Graph Cloud Benchmark: CognoDB and Managed Alternatives

This repository is a reproducible benchmark harness for CognoDB Cloud and managed graph platforms. It deliberately contains **no claimed performance results**: benchmark numbers must be generated from the JSON artifacts in `results/` using the exact accounts, region, and date stated in a published run. Empty cells below mean “not measured,” not zero or a loss.
.
## Scope

The workload uses the [SNAP Wiki-Vote network](https://snap.stanford.edu/data/wiki-Vote.html): **7,115 nodes and 103,689 directed relationships**. The loader downloads the original `wiki-Vote.txt.gz`, treats each vertex as `:Person {id}`, and each edge as `:VOTED_FOR`. This safely exceeds the assignment's 100,000-relationship threshold while being viable on very small tiers.

| Target | Access/query protocol | Tier chosen | Advertised vCPU / RAM / disk | Status |
|---|---|---|---|---|
| CognoDB Cloud | Bolt / Cypher | c0 free | 0.5 burstable / 256 MB / 1 GB | configured |
| Neo4j AuraDB | Bolt / Cypher | select only a tier matching the CognoDB envelope | record from console | supported by Bolt adapter |
| Memgraph Cloud | Bolt / Cypher | select only a tier matching the CognoDB envelope | record from console | supported by Bolt adapter |
| ArangoGraph | HTTP / AQL | select only a tier matching the CognoDB envelope | record from console | adapter pending |
| Amazon Neptune | Gremlin/openCypher | select only a tier matching the CognoDB envelope | record from console | adapter pending |

Do not publish a comparison until each row has genuinely comparable resources. Provider free tiers may have no equivalent 256 MB offering; in that case, label the run **resource mismatch** and exclude it from any winner/ranking claim. Record region, tier, advertised resources, service version, test-machine location, timestamp, and all failed/time-out runs in the final report.

## Run it

Prerequisites: Java 21+ and Maven 3.9+ (or the included project configuration), a local clone, and accounts/instances in one region as close as practical to the client machine. The official Neo4j Java driver is used for CognoDB's Bolt/Cypher endpoint.

```powershell
Copy-Item .env.example .env
# Fill credentials in .env; do not commit it.
# Load .env into this PowerShell process, then run:
Get-Content .env | Where-Object { $_ -match '^[^#=]+=' } | ForEach-Object { $name, $value = $_ -split '=', 2; Set-Item "Env:$name" $value }
mvn exec:java -Dexec.args=prepare-data
mvn exec:java -Dexec.args=benchmark
```

For a Bolt-compatible service, set its three URI/user/password variables and add its target name to `BENCHMARK_TARGETS`; e.g. `cognodb,neo4j-aura,memgraph`. The runner resets the target database, creates a uniqueness constraint, loads batches of 1,000, performs 20 warm-ups per read workload, then records 100 client-observed samples. It runs a deterministic shuffled set of start nodes, so identical data and settings reproduce the selection. Results land in `results/<target>-<epoch>.json`.

The benchmark is intentionally destructive **only to the configured benchmark database**: it executes `MATCH (n) DETACH DELETE n`. Use a dedicated empty instance.

## Logical workload and metrics

| Requirement | Operation | Reported output |
|---|---|---|
| Load | batched node then relationship writes | total ms, nodes/s, relationships/s |
| Traversal | 1-, 2-, 3-hop outgoing patterns, capped at 1,000 matches | p50/p95 ms |
| Lookup | `Person.id` point lookup; `id STARTS WITH` filtered lookup | p50/p95 ms |
| Aggregation | count by `VOTED_FOR` relationship type | p50/p95 ms |
| Mixed | 90% 1-hop reads / 10% timestamp writes, 10 client promises | sustained QPS |
| Footprint | provider console observation | disk/memory/spec, or “not observable” |

`Person.id` has a unique constraint in Cypher targets; this is the point-lookup index. Prefix filtering may not use that index and must be reported as such. Traversal queries use `WITH n LIMIT 1000` after the requested depth so hub nodes cannot create an unbounded result set; every target must use the equivalent cap. Each latency is client-observed end-to-end wall time (driver, network, and server), nearest-rank p50/p95—not server execution time. Change `BENCHMARK_*` variables only before a complete run, and state deviations in the results.

## Results matrix (measured 2026-08-24)

| Target | Load nodes/s | Load rels/s | 1-hop p50/p95 | 2-hop p50/p95 | 3-hop p50/p95 | Point p50/p95 | Filter p50/p95 | Aggregate p50/p95 | Mixed QPS | Footprint |
|---|---:|---:|---|---|---|---|---|---|---:|---|
| CognoDB Cloud | 134.57 | 1,961.09 | 607.66 / 699.59 | 617.18 / 814.61 | 615.71 / 938.07 | 611.72 / 887.66 | 613.85 / 877.88 | 699.01 / 919.29 | 13.90 | c0 free |
| Neo4j AuraDB | 270.52 | 3,942.31 | 192.47 / 879.97 | 148.42 / 527.24 | 156.34 / 551.31 | 144.07 / 624.89 | 142.47 / 564.73 | 182.23 / 779.24 | 36.98 | AuraDB Free |
| Memgraph Cloud | 26.02 | 379.27 | 522.54 / 1,809.11 | 496.88 / 521.00 | 496.30 / 663.01 | 502.72 / 1,004.63 | 496.01 / 695.58 | 601.50 / 1,589.56 | 12.76 | 2 GB / 2 CPU trial |
| ArangoGraph | 104.08 | 1,516.60 | 358.57 / 1,069.93 | 289.99 / 904.48 | 304.42 / 1,025.75 | 257.64 / 371.92 | 261.80 / 659.73 | 348.52 / 1,709.50 | 2.00 | 2 GB / 2 CPU trial |
| Amazon Neptune | not measured | not measured | not measured | not measured | not measured | not measured | not measured | not measured | not measured | ingest failed: MemoryLimitExceeded |

Artifacts: `results/cognodb-1787558648718.json`, `results/neo4j-aura-1787564212638.json`, `results/memgraph-1787563778030.json`, and `results/arangograph-1787569742779.txt`. Neptune IAM connectivity and `RETURN 1` succeeded, but the dataset load exceeded the selected instance memory before query sampling.

### Interpretation

In this run, Neo4j Aura had the highest ingest throughput and mixed-workload QPS among completed targets. ArangoGraph had competitive point lookups but lower mixed QPS; Memgraph had the lowest ingest throughput and high p95 variance. These are directional observations, not a provider-wide ranking: tiers, regions, network path, cache state, and query translations differ. Neptune is a capacity finding, not a performance score.

## Analysis protocol

Do not infer that lower client latency means a faster engine without checking region/network conditions, tier throttling, query plans, index behavior, and error rates. Explain outcomes in terms of the measured setup: different storage engines, traversal planners, caches, serverless cold starts, and rate limits are plausible contributors, not proof. Include raw JSON, a timestamped resource table, rerun variance, and all caveats with any public post.

## Known limitations and extension plan

The Java harness includes protocol-specific ArangoGraph and Neptune paths. ArangoGraph completed the workload; Neptune completed connectivity validation but not the full workload because the selected tier ran out of memory during ingest. Contributions should add translation tests and preserve the raw evidence artifacts.

## Security

All secrets come from environment variables. `.env`, downloaded data, and result files are ignored by git. Remove connection URIs/passwords from terminal captures and public issue text.
