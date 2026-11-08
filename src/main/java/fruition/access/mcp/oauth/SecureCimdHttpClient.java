package fruition.access.mcp.oauth;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.FETCH_FAILED;
import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.INVALID_METADATA;
import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.REDIRECT_RESPONSE;
import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.RESPONSE_TOO_LARGE;
import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.TIMEOUT;

final class SecureCimdHttpClient implements CimdDocumentFetcher {
    private static final ExecutorService FETCHES = Executors.newVirtualThreadPerTaskExecutor();

    private final McpClientMetadataSettings settings;
    private final DnsResolver dnsResolver;
    private final SSLContext sslContext;

    SecureCimdHttpClient(McpClientMetadataSettings settings, DnsResolver dnsResolver, SSLContext sslContext) {
        this.settings = settings;
        this.dnsResolver = dnsResolver;
        this.sslContext = sslContext;
    }

    @Override
    public byte[] fetch(String clientId) {
        URI uri = URI.create(clientId);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new McpClientMetadataException(FETCH_FAILED, "CIMD URL is invalid");
        }

        long timeoutNanos = settings.timeout().toNanos();
        long deadline = System.nanoTime() + timeoutNanos;
        Timeout apacheTimeout = Timeout.of(settings.timeout());
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                .setTlsSocketStrategy(ClientTlsStrategyBuilder.create().setSslContext(sslContext).buildClassic())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(apacheTimeout)
                        .setSocketTimeout(apacheTimeout)
                        .build())
                .setMaxConnPerRoute(1)
                .setMaxConnTotal(1)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setAuthenticationEnabled(false)
                .setRedirectsEnabled(false)
                .setContentCompressionEnabled(false)
                .setConnectionRequestTimeout(apacheTimeout)
                .setResponseTimeout(apacheTimeout)
                .setHardCancellationEnabled(true)
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .setDefaultRequestConfig(requestConfig)
                .setConnectionReuseStrategy((request, response, context) -> false)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableContentCompression()
                .build();
        HttpGet request = new HttpGet(uri);
        request.setHeader("Accept", "application/json");
        Future<byte[]> future = FETCHES.submit(() -> client.execute(request,
                response -> readResponse(response.getCode(), response.getEntity(), deadline)));
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            request.cancel();
            client.close(CloseMode.IMMEDIATE);
            future.cancel(true);
            throw new McpClientMetadataException(TIMEOUT, "CIMD retrieval timed out");
        } catch (InterruptedException interrupted) {
            request.cancel();
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpClientMetadataException(FETCH_FAILED, "CIMD retrieval was interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof McpClientMetadataException expected) throw expected;
            throw new McpClientMetadataException(FETCH_FAILED, "CIMD retrieval failed");
        } finally {
            client.close(CloseMode.IMMEDIATE);
        }
    }

    private byte[] readResponse(int status, HttpEntity entity, long deadline) throws IOException {
        if (status >= 300 && status < 400) {
            throw new McpClientMetadataException(REDIRECT_RESPONSE, "CIMD redirects are forbidden");
        }
        if (status != 200 || entity == null || !isJson(entity.getContentType())) {
            throw new McpClientMetadataException(INVALID_METADATA, "CIMD response is not valid JSON metadata");
        }
        if (entity.getContentLength() > settings.maxResponseBytes()) {
            throw new McpClientMetadataException(RESPONSE_TOO_LARGE, "CIMD response is too large");
        }
        try (InputStream body = entity.getContent();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(settings.maxResponseBytes(), 8192))) {
            byte[] buffer = new byte[Math.min(settings.maxResponseBytes(), 8192)];
            int total = 0;
            while (true) {
                requireBeforeDeadline(deadline);
                int read = body.read(buffer);
                requireBeforeDeadline(deadline);
                if (read == -1) return bytes.toByteArray();
                total += read;
                if (total > settings.maxResponseBytes()) {
                    throw new McpClientMetadataException(RESPONSE_TOO_LARGE, "CIMD response is too large");
                }
                bytes.write(buffer, 0, read);
            }
        }
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) return false;
        try {
            String mime = ContentType.parse(contentType).getMimeType().toLowerCase(Locale.ROOT);
            return "application/json".equals(mime) || (mime.startsWith("application/") && mime.endsWith("+json"));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static void requireBeforeDeadline(long deadline) {
        if (System.nanoTime() - deadline >= 0) {
            throw new McpClientMetadataException(TIMEOUT, "CIMD retrieval timed out");
        }
    }
}
