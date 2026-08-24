import pLimit from "p-limit";
import { latency, elapsedMs } from "./stats.js";
import type { BenchmarkResult, Dataset } from "./types.js";
import { BoltAdapter } from "./adapters/bolt.js";

const timed = async (fn: () => Promise<void>) => { const started = process.hrtime.bigint(); await fn(); return elapsedMs(started); };
async function samples(fn: (id: string) => Promise<void>, ids: string[], warmup: number, iterations: number) {
  for (let i = 0; i < warmup; i++) await fn(ids[i % ids.length]);
  const values: number[] = [];
  for (let i = 0; i < iterations; i++) values.push(await timed(() => fn(ids[i % ids.length])));
  return latency(values);
}
export async function benchmark(adapter: BoltAdapter, dataset: Dataset): Promise<BenchmarkResult> {
  const iterations = Number(process.env.BENCHMARK_ITERATIONS ?? 100);
  const warmup = Number(process.env.BENCHMARK_WARMUP ?? 20);
  const concurrency = Number(process.env.BENCHMARK_CONCURRENCY ?? 10);
  const writeRatio = Number(process.env.BENCHMARK_WRITE_RATIO ?? 0.1);
  if (!Number.isInteger(iterations) || iterations < 1 || !dataset.nodes.length) throw new Error("Invalid benchmark configuration or empty dataset");
  console.log(`${adapter.name}: verifying connection`); await adapter.verify();
  console.log(`${adapter.name}: resetting benchmark graph`); await adapter.reset(); await adapter.createSchema();
  console.log(`${adapter.name}: ingesting ${dataset.nodes.length} nodes and ${dataset.edges.length} relationships`);
  const ingestStart = process.hrtime.bigint(); await adapter.ingest(dataset); const ingestMs = elapsedMs(ingestStart);
  const ids = shuffle(dataset.nodes, 42);
  console.log(`${adapter.name}: measuring read workloads`);
  const queries = {
    oneHop: await samples(id => adapter.oneHop(id), ids, warmup, iterations),
    twoHop: await samples(id => adapter.twoHop(id), ids, warmup, iterations),
    threeHop: await samples(id => adapter.threeHop(id), ids, warmup, iterations),
    pointLookup: await samples(id => adapter.pointLookup(id), ids, warmup, iterations),
    filteredLookup: await samples(id => adapter.filteredLookup(id.slice(0, 1)), ids, warmup, iterations),
    aggregation: await samples(() => adapter.aggregation(), ids, warmup, iterations)
  };
  console.log(`${adapter.name}: measuring mixed workload at concurrency ${concurrency}`);
  const limit = pLimit(concurrency); let executed = 0; const mixedStart = process.hrtime.bigint();
  await Promise.all(Array.from({ length: iterations }, (_, i) => limit(async () => { if ((i % 100) < writeRatio * 100) await adapter.write(ids[i % ids.length]); else await adapter.oneHop(ids[i % ids.length]); executed++; })));
  const mixedMs = elapsedMs(mixedStart);
  return { target: adapter.name, startedAt: new Date().toISOString(), dataset: { nodes: dataset.nodes.length, relationships: dataset.edges.length, source: dataset.source }, ingest: { nodesPerSecond: dataset.nodes.length / (ingestMs / 1000), relationshipsPerSecond: dataset.edges.length / (ingestMs / 1000), totalMs: ingestMs }, queries, mixed: { qps: executed / (mixedMs / 1000), concurrency, writeRatio, durationMs: mixedMs }, caveats: ["Client-observed end-to-end latency includes network transit and driver overhead.", "Resource footprint is provider-specific; record any console-visible values separately."] };
}
function shuffle<T>(items: T[], seed: number): T[] { const out = [...items]; let state = seed; for (let i = out.length - 1; i > 0; i--) { state = (state * 1664525 + 1013904223) >>> 0; const j = state % (i + 1); [out[i], out[j]] = [out[j], out[i]]; } return out; }
