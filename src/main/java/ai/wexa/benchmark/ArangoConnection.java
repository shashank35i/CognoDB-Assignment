package ai.wexa.benchmark;

import com.arangodb.ArangoDB;
import com.arangodb.entity.ArangoDBVersion;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

/** Builds a TLS-pinned ArangoGraph client from environment variables. */
final class ArangoConnection {
  private ArangoConnection() {}

  static ArangoDB connect() throws Exception {
    String certificate = required("ARANGO_CA_BASE64");
    X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509")
        .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certificate)));
    KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null);
    store.setCertificateEntry("arangograph-ca", ca);
    TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(store);
    SSLContext context = SSLContext.getInstance("TLS"); context.init(null, managers.getTrustManagers(), null);
    return new ArangoDB.Builder().useSsl(true).host(required("ARANGO_HOST"), Integer.parseInt(System.getenv().getOrDefault("ARANGO_PORT", "18529")))
        .user(required("ARANGO_USER")).password(required("ARANGO_PASSWORD")).sslContext(context).build();
  }

  static void verify() throws Exception { ArangoDB client = connect(); try { ArangoDBVersion version = client.getVersion(); System.out.println("ArangoDB server version: " + version.getVersion()); } finally { client.shutdown(); } }
  private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable: " + name); return value; }
}
