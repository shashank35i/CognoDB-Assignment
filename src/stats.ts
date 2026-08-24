import type { Latency } from "./types.js";
export function percentile(values: number[], p: number): number {
  if (!values.length) throw new Error("Cannot calculate percentile of no samples");
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}
export function latency(values: number[]): Latency { return { p50Ms: percentile(values, 50), p95Ms: percentile(values, 95), samples: values.length }; }
export const elapsedMs = (start: bigint) => Number(process.hrtime.bigint() - start) / 1e6;
