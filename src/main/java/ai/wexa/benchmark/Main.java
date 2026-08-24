package ai.wexa.benchmark;

import org.neo4j.driver.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

/** Credential-free benchmark entry point. Configuration is read solely from environment variables. */
public final class Main {
  static final URI DATA_URL = URI.create("https://snap.stanford.edu/data/wiki-Vote.txt.gz");
  static final Path DATA_PATH = Path.of("data", "wiki-Vote.txt.gz");
  record Edge(String source, String target) {}
  record Dataset(List<String> nodes, List<Edge> edges) {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !(args[0].equals("prepare-data") || args[0].equals("benchmark") || args[0].equals("benchmark-cognodb") || args[0].equals("benchmark-memgraph") || args[0].equals("benchmark-aura") || args[0].equals("benchmark-arango") || args[0].equals("benchmark-neptune") || args[0].equals("verify-neptune") || args[0].equals("verify-memgraph") || args[0].equals("verify-aura") || args[0].equals("verify-arango"))) throw new IllegalArgumentException("Usage: java ... Main prepare-data|benchmark-cognodb|benchmark-memgraph|benchmark-aura|benchmark-arango|benchmark-neptune|verify-neptune");
    if (args[0].equals("prepare-data")) { Dataset d = dataset(); System.out.printf("Ready: %d nodes, %d relationships%n", d.nodes.size(), d.edges.size()); return; }
    if (args[0].equals("verify-memgraph")) { verifyMemgraph(); return; }
    if (args[0].equals("verify-aura")) { verifyBolt("NEO4J_AURA"); return; }
    if (args[0].equals("verify-arango")) { ArangoConnection.verify(); return; }
    if (args[0].equals("verify-neptune")) { NeptuneBenchmark.verify(); return; }
    if (args[0].equals("benchmark-neptune")) { NeptuneBenchmark.run(dataset()); return; }
    if (args[0].equals("benchmark") || args[0].equals("benchmark-cognodb")) runBenchmark(dataset(), "COGNODB", "cognodb");
    else if (args[0].equals("benchmark-memgraph")) runBenchmark(dataset(), "MEMGRAPH", "memgraph");
    else if (args[0].equals("benchmark-arango")) { ArangoBenchmark.run(dataset()); }
    else runBenchmark(dataset(), "NEO4J_AURA", "neo4j-aura");
  }

  static void verifyMemgraph() {
    verifyBolt("MEMGRAPH");
    System.out.println("Memgraph Bolt connectivity verified.");
  }
  static void verifyBolt(String prefix) {
    Config config = Config.builder().withConnectionTimeout(15, TimeUnit.SECONDS).build();
    try (Driver driver = GraphDatabase.driver(required(prefix + "_URI"), AuthTokens.basic(required(prefix + "_USER"), required(prefix + "_PASSWORD")), config)) {
      driver.verifyConnectivity();
    }
  }

  static Dataset dataset() throws Exception {
    if (!Files.exists(DATA_PATH)) { Files.createDirectories(DATA_PATH.getParent()); HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(); HttpRequest request = HttpRequest.newBuilder(DATA_URL).timeout(Duration.ofSeconds(60)).build(); HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray()); if (response.statusCode() != 200) throw new IOException("Dataset download failed: HTTP " + response.statusCode()); Files.write(DATA_PATH, response.body()); }
    Set<String> nodes = new HashSet<>(); List<Edge> edges = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(DATA_PATH)), StandardCharsets.UTF_8))) {
      for (String line; (line = reader.readLine()) != null;) { if (line.isBlank() || line.startsWith("#")) continue; String[] parts = line.trim().split("\\s+"); if (parts.length != 2) throw new IOException("Malformed edge: " + line); nodes.add(parts[0]); nodes.add(parts[1]); edges.add(new Edge(parts[0], parts[1])); }
    }
    return new Dataset(new ArrayList<>(nodes), edges);
  }

  static void runBenchmark(Dataset data, String prefix, String target) throws Exception {
    String uri = required(prefix + "_URI"), user = required(prefix + "_USER"), password = required(prefix + "_PASSWORD"); int iterations = integer("BENCHMARK_ITERATIONS", 100), warmup = integer("BENCHMARK_WARMUP", 20), concurrency = integer("BENCHMARK_CONCURRENCY", 10); double writeRatio = decimal("BENCHMARK_WRITE_RATIO", .10);
    Config config = Config.builder().withConnectionTimeout(15, TimeUnit.SECONDS).build();
    try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password), config)) {
      System.out.println("cognodb: verifying connection"); driver.verifyConnectivity();
      System.out.println("cognodb: resetting benchmark graph"); execute(driver, "MATCH (n) DETACH DELETE n", Map.of()); execute(driver, "CREATE CONSTRAINT person_id IF NOT EXISTS FOR (n:Person) REQUIRE n.id IS UNIQUE", Map.of());
      System.out.printf("cognodb: ingesting %d nodes and %d relationships%n", data.nodes.size(), data.edges.size()); long loadStart = System.nanoTime(); ingest(driver, data); double loadMs = elapsed(loadStart);
      List<String> ids = new ArrayList<>(data.nodes); Collections.shuffle(ids, new Random(42));
      System.out.println("cognodb: measuring read workloads"); Map<String, Latency> metrics = new LinkedHashMap<>();
      metrics.put("oneHop", sample(iterations, warmup, i -> execute(driver, "MATCH (:Person {id:$id})-[:VOTED_FOR]->(n) WITH n LIMIT 1000 RETURN count(n)", Map.of("id", ids.get(i % ids.size())))));
      metrics.put("twoHop", sample(iterations, warmup, i -> execute(driver, "MATCH (:Person {id:$id})-[:VOTED_FOR]->()-[:VOTED_FOR]->(n) WITH n LIMIT 1000 RETURN count(n)", Map.of("id", ids.get(i % ids.size())))));
      metrics.put("threeHop", sample(iterations, warmup, i -> execute(driver, "MATCH (:Person {id:$id})-[:VOTED_FOR]->()-[:VOTED_FOR]->()-[:VOTED_FOR]->(n) WITH n LIMIT 1000 RETURN count(n)", Map.of("id", ids.get(i % ids.size())))));
      metrics.put("pointLookup", sample(iterations, warmup, i -> execute(driver, "MATCH (n:Person {id:$id}) RETURN n.id", Map.of("id", ids.get(i % ids.size())))));
      metrics.put("filteredLookup", sample(iterations, warmup, i -> execute(driver, "MATCH (n:Person) WHERE n.id STARTS WITH $prefix RETURN n.id LIMIT 25", Map.of("prefix", ids.get(i % ids.size()).substring(0, 1)))));
      metrics.put("aggregation", sample(iterations, warmup, i -> execute(driver, "MATCH (:Person)-[r:VOTED_FOR]->() RETURN type(r), count(r)", Map.of())));
      System.out.printf("cognodb: measuring mixed workload at concurrency %d%n", concurrency); double mixedMs = mixed(driver, ids, iterations, concurrency, writeRatio);
      writeResult(target, data, loadMs, metrics, iterations / (mixedMs / 1000), concurrency, writeRatio, mixedMs);
    }
  }

  interface Task { void run(int index); }
  record Latency(double p50Ms, double p95Ms, int samples) {}
  static Latency sample(int count, int warmup, Task task) { for (int i=0;i<warmup;i++) task.run(i); double[] values = new double[count]; for(int i=0;i<count;i++){ long start=System.nanoTime(); task.run(i); values[i]=elapsed(start); } Arrays.sort(values); return new Latency(values[(int)Math.ceil(.50*count)-1], values[(int)Math.ceil(.95*count)-1], count); }
  static void ingest(Driver driver, Dataset data) { for (int i=0;i<data.nodes.size();i+=5000) { List<Map<String,String>> rows=data.nodes.subList(i, Math.min(i+5000,data.nodes.size())).stream().map(id->Map.of("id",id)).toList(); execute(driver,"UNWIND $rows AS row CREATE (:Person {id: row.id})",Map.of("rows",rows)); } for(int i=0;i<data.edges.size();i+=5000) { List<Map<String,String>> rows=data.edges.subList(i,Math.min(i+5000,data.edges.size())).stream().map(e->Map.of("source",e.source,"target",e.target)).toList(); execute(driver,"UNWIND $rows AS row MATCH (a:Person {id: row.source}), (b:Person {id: row.target}) CREATE (a)-[:VOTED_FOR]->(b)",Map.of("rows",rows)); } }
  static double mixed(Driver driver,List<String> ids,int count,int concurrency,double writeRatio) throws Exception { ExecutorService pool=Executors.newFixedThreadPool(concurrency); long start=System.nanoTime(); List<Future<?>> jobs=new ArrayList<>(); for(int i=0;i<count;i++){ int n=i; jobs.add(pool.submit(()->{ String id=ids.get(n%ids.size()); if ((n%100)<writeRatio*100) execute(driver,"MATCH (n:Person {id:$id}) SET n.lastBenchWrite = timestamp()",Map.of("id",id)); else execute(driver,"MATCH (:Person {id:$id})-[:VOTED_FOR]->(n) RETURN count(n)",Map.of("id",id)); })); } for(Future<?> job:jobs) job.get(); pool.shutdown(); return elapsed(start); }
  static void execute(Driver d,String cypher,Map<String,Object> params) { try(Session s=d.session()){ s.run(cypher,params).consume(); } }
  static double elapsed(long start){return (System.nanoTime()-start)/1_000_000.0;}
  static String required(String name){String v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalArgumentException("Missing environment variable: "+name);return v;}
  static int integer(String n,int fallback){return Integer.parseInt(System.getenv().getOrDefault(n,""+fallback));} static double decimal(String n,double fallback){return Double.parseDouble(System.getenv().getOrDefault(n,""+fallback));}
  static void writeResult(String target, Dataset d,double loadMs,Map<String,Latency> m,double qps,int concurrency,double writes,double mixedMs) throws IOException { Files.createDirectories(Path.of("results")); String file="results/"+target+"-"+Instant.now().toEpochMilli()+".json"; StringBuilder json=new StringBuilder("{\n  \"target\": \"").append(target).append("\",\n  \"dataset\": {\"nodes\": ").append(d.nodes.size()).append(", \"relationships\": ").append(d.edges.size()).append("},\n  \"ingest\": {\"nodesPerSecond\": ").append(d.nodes.size()/(loadMs/1000)).append(", \"relationshipsPerSecond\": ").append(d.edges.size()/(loadMs/1000)).append(", \"totalMs\": ").append(loadMs).append("},\n  \"queries\": {"); boolean first=true; for(var e:m.entrySet()){if(!first)json.append(',');first=false;Latency l=e.getValue();json.append("\n    \"").append(e.getKey()).append("\": {\"p50Ms\": ").append(l.p50Ms).append(", \"p95Ms\": ").append(l.p95Ms).append(", \"samples\": ").append(l.samples).append('}');} json.append("\n  },\n  \"mixed\": {\"qps\": ").append(qps).append(", \"concurrency\": ").append(concurrency).append(", \"writeRatio\": ").append(writes).append(", \"durationMs\": ").append(mixedMs).append("}\n}\n"); Files.writeString(Path.of(file),json); System.out.println("Saved "+file); }
}
