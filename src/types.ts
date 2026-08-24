export type Edge = { source: string; target: string };
export type Dataset = { nodes: string[]; edges: Edge[]; source: string };
export type Latency = { p50Ms: number; p95Ms: number; samples: number };
export type BenchmarkResult = {
  target: string; startedAt: string; dataset: { nodes: number; relationships: number; source: string };
  ingest: { nodesPerSecond: number; relationshipsPerSecond: number; totalMs: number };
  queries: Record<string, Latency>; mixed: { qps: number; concurrency: number; writeRatio: number; durationMs: number };
  caveats: string[];
};
