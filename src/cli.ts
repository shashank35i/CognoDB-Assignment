import "dotenv/config";
import { mkdir, writeFile } from "node:fs/promises";
import { downloadDataset, loadDataset } from "./dataset.js";
import { BoltAdapter } from "./adapters/bolt.js";
import { benchmark } from "./runner.js";

const targets = {
  cognodb: ["COGNODB_URI", "COGNODB_USER", "COGNODB_PASSWORD"],
  "neo4j-aura": ["NEO4J_AURA_URI", "NEO4J_AURA_USER", "NEO4J_AURA_PASSWORD"],
  memgraph: ["MEMGRAPH_URI", "MEMGRAPH_USER", "MEMGRAPH_PASSWORD"]
} as const;
async function main() {
  const command = process.argv[2];
  if (command === "prepare-data") { await downloadDataset(); const data = await loadDataset(); console.log(`Ready: ${data.nodes.length} nodes, ${data.edges.length} relationships`); return; }
  if (command !== "benchmark") throw new Error("Usage: npm run prepare-data | npm run benchmark");
  const dataset = await loadDataset(); const selected = (process.env.BENCHMARK_TARGETS ?? "cognodb").split(",").map(x => x.trim());
  for (const target of selected) {
    if (!(target in targets)) throw new Error(`Unsupported target '${target}'. Add an adapter before selecting it.`);
    const [uriKey, userKey, passwordKey] = targets[target as keyof typeof targets]; const [uri, user, password] = [process.env[uriKey], process.env[userKey], process.env[passwordKey]];
    if (!uri || !user || !password) throw new Error(`${target}: set ${uriKey}, ${userKey}, and ${passwordKey} in .env`);
    const adapter = new BoltAdapter({ name: target, uri, user, password });
    try { const result = await benchmark(adapter, dataset); await mkdir("results", { recursive: true }); const path = `results/${target}-${Date.now()}.json`; await writeFile(path, JSON.stringify(result, null, 2)); console.log(`Saved ${path}`); } finally { await adapter.close(); }
  }
}
main().catch(error => { console.error(error instanceof Error ? error.message : error); process.exitCode = 1; });
