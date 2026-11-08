package fruition.access.mcp.oauth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.FETCH_FAILED;
import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.REDIRECT_RESPONSE;
import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.RESPONSE_TOO_LARGE;
import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(5)
class SecureCimdHttpClientTest {
    @Test
    void rejectsTimeoutThatCannotFitTheOAuthEndpointBudget() {
        assertThatThrownBy(() -> new McpClientMetadataSettings(64, Duration.ofMillis(2001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final String PRIVATE_KEY = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQClV3poaJvQycMwBUmU1gV/GJNrGMJin17ukkgJ/BnAlpnh+IOwLJOGjHVSKk8vtjrkxAsCDZEMPw4fuGFhZf3I5dzEb/4itzwsBmHPlBtmVPN6yy2UA+dxcgsb8ZfEDyki7aJLaLl3NyOtcglne3Milt8cdzIvYI3ws4JU7dibR7Nbmg0SmcXi/QhL4K2EY0g+80gVZ8uXCe8w1DL9dBeXZ9I62EuD9zfrQjVOOYcBFab3LHlrG7OQnz4hExHtgyVVozo2aA4uENQsMIqMOjFU5Ix0ZIv3G1c++A6CgpYJz2T0HmjyrXf5Ta23YKPYGxZhbLK1UQrSFAbTZQuUu5A1AgMBAAECggEAP27DOIFBniV569drdl1xuGP+Uq0qDlokOpUGbEY6vwdIPEhyhUlkw87pR7+Rp7Va/N81dfEsf61FRuge/BX7+nItc3Of5UO1IxLDvbaNmIHo82g1sT6aMYzWND9IwWAroQnYJOQlm1YdKvLAJuJWtqN5L1uPdlutuPW1f83MNy36BV/TAdneQBSnksCfTUwe8aPEJ6hwY+peqXsqBO/zzPjF0178rZmffAmnHqB2W6tNCflc/EzPK0p35jDUjYX9x3K5z48UV9lzB3cQb77UKL6rD1Cpi+fvQmcyuNVAU67po7DQPYRrmR54MWk/zy4cRcj0jWpPnJbnJb+TJcx49QKBgQDOxPZ3BO0fYhwDrAUSpOS+eREm6uvzE24GRgMDI5z5oFj5Uf6Q/xFwqR3nW+uKfaSvtrVjl9ubemW8zr/mpcCRp/Ni/LeQDtp+ykwS5V9ijBa/lNm8g75hpyKwl/InHvwRA7QU3ds/+9m9r7mxGO7jIWDjeKOxAhi/xd/bniiYewKBgQDMtWjs1ftAp+LihYabadmUvb2pG8Z3psJHbQX0MemuWneEkpUVKU9AFrmzKRjRP/MpuFIdnh/AhczQHIwt6e36h7bFmQrwMFxFodAF/qY66KoVrEenIqCGGXFPNqcbPob98BW9NGNZLVZGZI5jOGXDZPkaKSuYHZogOR8TVqSTDwKBgBRgAFF2IM+o4U3TU6Skrw297FzDZYDMd1kS6RUUG1HV5S6cT9OF7vgFEjdMLYe2HdZa0MEBo9hMVV4nmW1nA/TWcftdH9GIRSudT6XFCIMzfsJeLp6O9OG2kIqKTohgW0Jp9QLhYcuKS8iPWmMDK/chwORpbOfyZvHu08JWcXWlAoGBAJWzHDYGDaUsoPiyWMuSTrE9SFNAopSheHWvPqljTtZYBgsG/sro2LGvbMGeDi4HrBUYW8SY+/NYZXzTQ5fX+FY7pOc/kgYv92pceLnJjVqLBsauo/+PjQBD3t7kwrNjuGg/iEZC51wxkWQM6SuZ9KE9WAVPkje9fhaObzE/LT71AoGAVNb15E4boCfbukkr7dvYW8BkzSUuwlUahOzUVh5AoBlHA5f3K/feH7Rxv3SCoB7UpJDx13VL+JBcX1Ifrwu1WI9xoDjfo+yE/0of0xkWDS0f/us/F++5ICzXHSitjdj+dXfQwcP3o27xcMnCFyJeywt2lg5ZMvsmjbemPmL/KHY=";
    private static final String CERTIFICATE = "MIIDMDCCAhigAwIBAgIUMy32Bie15kNkp7xr+8WW3GzT/twwDQYJKoZIhvcNAQELBQAwFjEUMBIGA1UEAwwLY2xpZW50LnRlc3QwHhcNMjYxMTA3MTQzNTU1WhcNMzYxMTA0MTQzNTU1WjAWMRQwEgYDVQQDDAtjbGllbnQudGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAKVXemhom9DJwzAFSZTWBX8Yk2sYwmKfXu6SSAn8GcCWmeH4g7Ask4aMdVIqTy+2OuTECwINkQw/Dh+4YWFl/cjl3MRv/iK3PCwGYc+UG2ZU83rLLZQD53FyCxvxl8QPKSLtoktouXc3I61yCWd7cyKW3xx3Mi9gjfCzglTt2JtHs1uaDRKZxeL9CEvgrYRjSD7zSBVny5cJ7zDUMv10F5dn0jrYS4P3N+tCNU45hwEVpvcseWsbs5CfPiETEe2DJVWjOjZoDi4Q1Cwwiow6MVTkjHRki/cbVz74DoKClgnPZPQeaPKtd/lNrbdgo9gbFmFssrVRCtIUBtNlC5S7kDUCAwEAAaN2MHQwHQYDVR0OBBYEFDybl84IL9v6hybBXaGwUQGJdgiJMB8GA1UdIwQYMBaAFDybl84IL9v6hybBXaGwUQGJdgiJMA8GA1UdEwEB/wQFMAMBAf8wIQYDVR0RBBowGIILY2xpZW50LnRlc3SCCWxvY2FsaG9zdDANBgkqhkiG9w0BAQsFAAOCAQEAFu2ZLVVJE/6aT9mKwRYaRhg9I+xvwJjJtrpa9PGLdSZd7D6OUscxPDKPBZ6hRVe3ClAYp04FPZlCCvR+PznOxiNNQiQ90TlP+1u3rBWmHKwMUANlN9084asQa4c0FilPWdVq4/8uYrOI+ULwua7Ld8wIGNj643o3y3KqnUTFYK5OvbnXqvn1smVhfTGBYA/lgZ4QXUiQbauOdIKOo2GM86G0QgRs6a6hEnDgBGW1fIqDAaRUBK2le9v5seSYfXn+Dt4SbuTBNRNz0h3Xshd4xvF9PLaODz5UsBuC3Pm/nGQDHCox/w6TyB1uNi3fe/82Sgftcsd1Il9Op3ttz5BPyA==";

    private HttpsServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    void performsARealPinnedTlsFetchAndKeepsHostnameVerification() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        start(exchange -> respond(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8)));
        SecureCimdHttpClient client = client(new McpClientMetadataSettings(64, Duration.ofSeconds(2)),
                mappingResolver(resolutions), clientSslContext());

        assertThat(client.fetch(url("client.test", "/metadata"))).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(resolutions).hasValue(1);
        assertThatThrownBy(() -> client.fetch(url("wrong.test", "/metadata")))
                .isInstanceOfSatisfying(McpClientMetadataException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(FETCH_FAILED));
    }

    @Test
    void refusesRedirectsWithoutCallingTheirTarget() throws Exception {
        AtomicInteger targetCalls = new AtomicInteger();
        start(exchange -> {
            if ("/target".equals(exchange.getRequestURI().getPath())) {
                targetCalls.incrementAndGet();
                respond(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            } else {
                exchange.getResponseHeaders().add("Location", url("client.test", "/target"));
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
        });

        assertThatThrownBy(() -> client(settings(), mappingResolver(new AtomicInteger()), clientSslContext())
                .fetch(url("client.test", "/redirect")))
                .isInstanceOfSatisfying(McpClientMetadataException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(REDIRECT_RESPONSE));
        assertThat(targetCalls).hasValue(0);
    }

    @Test
    void boundsChunkedResponseBytes() throws Exception {
        start(exchange -> respond(exchange, 200, "application/json", "123456789".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client(new McpClientMetadataSettings(8, Duration.ofSeconds(2)),
                mappingResolver(new AtomicInteger()), clientSslContext()).fetch(url("client.test", "/large")))
                .isInstanceOfSatisfying(McpClientMetadataException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(RESPONSE_TOO_LARGE));
    }

    @Test
    void totalDeadlineIncludesAStalledBodyRead() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(700);
                exchange.getResponseBody().write('}');
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        SecureCimdHttpClient client = client(new McpClientMetadataSettings(64, Duration.ofMillis(100)),
                mappingResolver(new AtomicInteger()), clientSslContext());
        long started = System.nanoTime();

        assertThatThrownBy(() -> client.fetch(url("client.test", "/slow")))
                .isInstanceOfSatisfying(McpClientMetadataException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverSslContext()));
        server.createContext("/", handler);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.start();
    }

    private String url(String host, String path) {
        return "https://" + host + ":" + server.getAddress().getPort() + path;
    }

    private static McpClientMetadataSettings settings() {
        return new McpClientMetadataSettings(64, Duration.ofSeconds(2));
    }

    private static SecureCimdHttpClient client(McpClientMetadataSettings settings, DnsResolver resolver,
                                               SSLContext sslContext) {
        return new SecureCimdHttpClient(settings, resolver, sslContext);
    }

    private static DnsResolver mappingResolver(AtomicInteger resolutions) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                resolutions.incrementAndGet();
                return new InetAddress[]{InetAddress.getLoopbackAddress()};
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, 0);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static SSLContext serverSslContext() throws Exception {
        X509Certificate certificate = certificate();
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(PRIVATE_KEY)));
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("server", privateKey, "test".toCharArray(),
                new java.security.cert.Certificate[]{certificate});
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "test".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext clientSslContext() throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("server", certificate());
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }

    private static X509Certificate certificate() throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(CERTIFICATE)));
    }
}
