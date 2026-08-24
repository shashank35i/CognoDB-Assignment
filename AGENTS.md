# Repository Guidelines

## Project Structure & Module Organization

`src/cli.ts` is the command-line entry point. It downloads the public SNAP data and orchestrates selected benchmark targets. `src/adapters/bolt.ts` holds the shared Bolt/Cypher adapter used by CognoDB, Neo4j AuraDB, and Memgraph. `src/runner.ts` implements the warm-up, percentile sampling, ingest, and mixed-workload protocol; it writes one JSON evidence file per target under `results/`. Dataset acquisition and parsing live in `src/dataset.ts`; do not commit downloaded dataset files or provider credentials.

## Build, Test, and Development Commands

- `npm install` installs pinned semver dependencies.
- `npm run prepare-data` downloads and verifies the SNAP Wiki-Vote dataset.
- `npm run benchmark` executes the targets named by `BENCHMARK_TARGETS` in `.env`.
- `npm run typecheck` checks strict TypeScript types.
- `npm test` runs the Vitest suite.

## Coding Style & Naming Conventions

The project uses strict TypeScript with NodeNext modules. Keep local imports suffixed with `.js`, use camelCase identifiers, and put provider-specific query translations in an adapter rather than the runner. Benchmark result fields are deliberately stable because README tables derive from the JSON artifacts.

## Testing Guidelines

Place unit tests in `test/` with the `.test.ts` suffix. Run an individual test with `npx vitest run test/stats.test.ts`. Networked benchmark execution is intentionally not part of the unit suite; it requires user-supplied cloud credentials.
