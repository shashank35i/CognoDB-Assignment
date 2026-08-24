package ai.wexa.benchmark;

import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.neptunedata.NeptunedataClient;
import software.amazon.awssdk.services.neptunedata.model.ExecuteOpenCypherQueryRequest;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

final class NeptuneBenchmark {
  static void verify() {
    String endpoint = required("NEPTUNE_ENDPOINT");
    try (NeptunedataClient client = NeptunedataClient.builder().region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"))).endpointOverride(URI.create("https://" + endpoint + ":8182")).credentialsProvider(ProfileCredentialsProvider.create(System.getenv().getOrDefault("AWS_PROFILE", "wexa-neptune"))).build()) {
      client.executeOpenCypherQuery(ExecuteOpenCypherQueryRequest.builder().openCypherQuery("RETURN 1").build());
      System.out.println("Neptune IAM OpenCypher connectivity verified.");
    }
  }
  static void run(Main.Dataset data) throws Exception {
    try (NeptunedataClient client = client()) {
      exec(client,"MATCH (n) DETACH DELETE n", "{}");
      long loadStart=System.nanoTime();
      for(int i=0;i<data.nodes().size();i+=1000){StringBuilder rows=new StringBuilder("{\"rows\":[");for(int j=i;j<Math.min(i+1000,data.nodes().size());j++){if(j>i)rows.append(',');rows.append("{\"id\":\"").append(data.nodes().get(j)).append("\"}");}rows.append("]}");exec(client,"UNWIND $rows AS row CREATE (:Person {id: row.id})",rows.toString());}
      for(int i=0;i<data.edges().size();i+=1000){StringBuilder rows=new StringBuilder("{\"rows\":[");for(int j=i;j<Math.min(i+1000,data.edges().size());j++){if(j>i)rows.append(',');Main.Edge e=data.edges().get(j);rows.append("{\"source\":\"").append(e.source()).append("\",\"target\":\"").append(e.target()).append("\"}");}rows.append("]}");exec(client,"UNWIND $rows AS row MATCH (a:Person {id: row.source}), (b:Person {id: row.target}) CREATE (a)-[:VOTED_FOR]->(b)",rows.toString());}
      double loadMs=(System.nanoTime()-loadStart)/1e6;List<String> ids=new ArrayList<>(data.nodes());Collections.shuffle(ids,new Random(42));StringBuilder out=new StringBuilder("target=neptune\nnodes=").append(data.nodes().size()).append(" relationships=").append(data.edges().size()).append(" loadMs=").append(loadMs).append("\n");
      for(String name:List.of("oneHop","twoHop","threeHop","pointLookup","filteredLookup","aggregation")){List<Double>a=new ArrayList<>();for(int i=0;i<20;i++)measure(client,name,ids,i);for(int i=0;i<100;i++)a.add(measure(client,name,ids,i));Collections.sort(a);out.append(name).append(" p50Ms=").append(a.get(49)).append(" p95Ms=").append(a.get(94)).append(" samples=100\n");}
      long mixed=System.nanoTime();for(int i=0;i<100;i++){String id=ids.get(i%ids.size());if(i%10==0)exec(client,"MATCH (n:Person {id: $id}) SET n.lastBenchWrite = timestamp()",json("id",id));else exec(client,"MATCH (:Person {id:$id})-[:VOTED_FOR]->(n) RETURN count(n)",json("id",id));}double mixedMs=(System.nanoTime()-mixed)/1e6;out.append("mixedQps=").append(100/(mixedMs/1000)).append(" durationMs=").append(mixedMs).append("\n");Files.writeString(Path.of("results/neptune-"+Instant.now().toEpochMilli()+".txt"),out.toString());
    }
  }
  private static double measure(NeptunedataClient c,String name,List<String>ids,int i){String id=ids.get(i%ids.size());long s=System.nanoTime();if(name.equals("oneHop"))exec(c,"MATCH (:Person {id:$id})-[:VOTED_FOR]->(n) RETURN count(n)",json("id",id));else if(name.equals("twoHop"))exec(c,"MATCH (:Person {id:$id})-[:VOTED_FOR]->()-[:VOTED_FOR]->(n) RETURN count(n)",json("id",id));else if(name.equals("threeHop"))exec(c,"MATCH (:Person {id:$id})-[:VOTED_FOR]->()-[:VOTED_FOR]->()-[:VOTED_FOR]->(n) RETURN count(n)",json("id",id));else if(name.equals("pointLookup"))exec(c,"MATCH (n:Person {id:$id}) RETURN n.id",json("id",id));else if(name.equals("filteredLookup"))exec(c,"MATCH (n:Person) WHERE n.id STARTS WITH $p RETURN n.id LIMIT 25",json("p",id.substring(0,1)));else exec(c,"MATCH (:Person)-[r:VOTED_FOR]->() RETURN type(r), count(r)","{}");return(System.nanoTime()-s)/1e6;}
  private static String json(String k,String v){return "{\""+k+"\":\""+v+"\"}";}
  private static NeptunedataClient client(){return NeptunedataClient.builder().region(Region.of(System.getenv().getOrDefault("AWS_REGION","us-east-1"))).endpointOverride(URI.create("https://"+required("NEPTUNE_ENDPOINT")+":8182")).credentialsProvider(ProfileCredentialsProvider.create(System.getenv().getOrDefault("AWS_PROFILE","wexa-neptune"))).build();}
  private static void exec(NeptunedataClient c,String q,String p){c.executeOpenCypherQuery(ExecuteOpenCypherQueryRequest.builder().openCypherQuery(q).parameters(p).build());}
  private static String required(String name) { String value=System.getenv(name); if(value==null||value.isBlank()) throw new IllegalArgumentException("Missing "+name); return value; }
}
