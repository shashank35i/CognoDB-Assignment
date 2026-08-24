import neo4j, { type Driver } from "neo4j-driver";
import type { Dataset } from "../types.js";

export type BoltConfig = { name: string; uri: string; user: string; password: string };
export class BoltAdapter {
  readonly name: string; private driver: Driver;
  constructor(config: BoltConfig) { this.name = config.name; this.driver = neo4j.driver(config.uri, neo4j.auth.basic(config.user, config.password), { connectionTimeout: 15_000 }); }
  async verify() { await this.driver.verifyConnectivity(); }
  async reset() { await this.run("MATCH (n) DETACH DELETE n"); }
  async createSchema() { await this.run("CREATE CONSTRAINT person_id IF NOT EXISTS FOR (n:Person) REQUIRE n.id IS UNIQUE"); }
  async ingest(dataset: Dataset, batchSize = 1000) {
    for (let i = 0; i < dataset.nodes.length; i += batchSize) await this.run("UNWIND $rows AS row CREATE (:Person {id: row.id})", { rows: dataset.nodes.slice(i, i + batchSize).map(id => ({ id })) });
    for (let i = 0; i < dataset.edges.length; i += batchSize) await this.run("UNWIND $rows AS row MATCH (a:Person {id: row.source}), (b:Person {id: row.target}) CREATE (a)-[:VOTED_FOR]->(b)", { rows: dataset.edges.slice(i, i + batchSize) });
  }
  async oneHop(id: string) { await this.run("MATCH (:Person {id:$id})-[:VOTED_FOR]->(n) RETURN count(n)", { id }); }
  async twoHop(id: string) { await this.run("MATCH (:Person {id:$id})-[:VOTED_FOR]->()-[:VOTED_FOR]->(n) RETURN count(n)", { id }); }
  async threeHop(id: string) { await this.run("MATCH (:Person {id:$id})-[:VOTED_FOR]->()-[:VOTED_FOR]->()-[:VOTED_FOR]->(n) RETURN count(n)", { id }); }
  async pointLookup(id: string) { await this.run("MATCH (n:Person {id:$id}) RETURN n.id", { id }); }
  async filteredLookup(prefix: string) { await this.run("MATCH (n:Person) WHERE n.id STARTS WITH $prefix RETURN n.id LIMIT 25", { prefix }); }
  async aggregation() { await this.run("MATCH (:Person)-[r:VOTED_FOR]->() RETURN type(r), count(r)"); }
  async write(id: string) { await this.run("MATCH (n:Person {id:$id}) SET n.lastBenchWrite = timestamp()", { id }); }
  private async run(query: string, params: Record<string, unknown> = {}) { const session = this.driver.session(); try { await session.run(query, params); } finally { await session.close(); } }
  async close() { await this.driver.close(); }
}
