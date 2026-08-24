import { createWriteStream, existsSync, mkdirSync } from "node:fs";
import { createInterface } from "node:readline";
import { pipeline } from "node:stream/promises";
import { createGunzip } from "node:zlib";
import { Readable } from "node:stream";
import type { Dataset, Edge } from "./types.js";

export const DATA_URL = "https://snap.stanford.edu/data/wiki-Vote.txt.gz";
export const DATA_FILE = "data/wiki-Vote.txt.gz";

export async function downloadDataset(): Promise<void> {
  if (existsSync(DATA_FILE)) return;
  mkdirSync("data", { recursive: true });
  const response = await fetch(DATA_URL, { signal: AbortSignal.timeout(60_000) });
  if (!response.ok || !response.body) throw new Error(`Dataset download failed: ${response.status}`);
  await pipeline(Readable.fromWeb(response.body as never), createWriteStream(DATA_FILE));
}

export async function loadDataset(): Promise<Dataset> {
  if (!existsSync(DATA_FILE)) throw new Error("Dataset missing. Run npm run prepare-data first.");
  const nodes = new Set<string>(); const edges: Edge[] = [];
  const lines = createInterface({ input: (await import("node:fs")).createReadStream(DATA_FILE).pipe(createGunzip()) });
  for await (const line of lines) {
    if (!line || line.startsWith("#")) continue;
    const [source, target] = line.trim().split(/\s+/);
    if (!source || !target) continue;
    nodes.add(source); nodes.add(target); edges.push({ source, target });
  }
  return { nodes: [...nodes], edges, source: DATA_URL };
}
