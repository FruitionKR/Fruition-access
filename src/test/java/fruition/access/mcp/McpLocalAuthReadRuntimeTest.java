package fruition.access.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.TestcontainersConfiguration;
import fruition.access.mcp.domain.McpScope;
import fruition.access.mcp.oauth.CimdDocumentFetcher;
import fruition.access.mcp.service.McpAuthorizationService;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceMemberId;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import fruition.access.workspace.service.AiInternalClient;
import fruition.access.workspace.service.DocumentInternalClient;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@EnabledIf("externalRuntimeConfigured")
class McpLocalAuthReadRuntimeTest {
    private static final String RUNTIME_DIR_ENV = "MCP_LOCAL_AUTH_READ_RUNTIME_DIR";
    private static final String RESOURCE = "https://127.0.0.1:59321/mcp";
    private static final String REGISTERED_REDIRECT = "http://127.0.0.1/callback";
    private static final String ACTUAL_REDIRECT = "http://127.0.0.1:43123/callback";
    private static final String PASSWORD = "local-integration-password-1234";
    private static final KeyPair RSA = rsa();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("app.mcp.oauth.enabled", () -> "true");
        properties.add("app.mcp.oauth.frontend-authorization-uri", () -> "http://127.0.0.1:3000/mcp/authorize");
        properties.add("app.mcp.oauth.issuer", () -> "https://access.local.test");
        properties.add("app.mcp.oauth.resource", () -> RESOURCE);
        properties.add("app.jwt.secret", () -> "");
        properties.add("app.jwt.public-key", () -> pem("PUBLIC KEY", RSA.getPublic().getEncoded()));
        properties.add("app.jwt.private-key", () -> pem("PRIVATE KEY", RSA.getPrivate().getEncoded()));
        properties.add("app.jwt.key-id", () -> "i1-i3-local-key");
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JwtTokenProvider jwt;
    @Autowired McpAuthorizationService authorization;
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @Autowired TransactionTemplate tx;
    @Autowired PasswordEncoder passwordEncoder;
    @MockitoBean CimdDocumentFetcher fetcher;
    @MockitoBean DocumentInternalClient documentClient;
    @MockitoBean AiInternalClient aiClient;

    private static boolean externalRuntimeConfigured() {
        String configured = System.getenv(RUNTIME_DIR_ENV);
        if (configured == null || configured.isBlank()) return false;
        Path runtimeDir = runtimeDir();
        if (!Files.isRegularFile(runtimeDir.resolve("access-isolation.init.gradle"))
                || !Files.isRegularFile(runtimeDir.resolve("document-integration.init.gradle"))
                || !Files.isRegularFile(runtimeDir.resolve(
                "java/fruition/core/integration/I1I3LocalAuthReadRuntimeTest.java"))) {
            throw new IllegalStateException(RUNTIME_DIR_ENV
                    + " must point to the configured paired Access/Document runtime directory");
        }
        return true;
    }

    @Test
    void servesRealOAuthAndCurrentAuthorizationToDocumentRuntime() throws Exception {
        Files.createDirectories(runtimeDir());
        clearControlFiles();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String userId = "user_local_auth_" + suffix;
        String workspaceId = "workspace_local_auth_" + suffix;
        String email = suffix + "@example.invalid";
        String clientId = "https://client.example/local-auth/" + suffix;
        tx.executeWithoutResult(status -> {
            User user = users.save(new User(userId, email, User.PROVIDER_LOCAL, "Local MCP Owner",
                    passwordEncoder.encode(PASSWORD)));
            Workspace workspace = workspaces.save(new Workspace(workspaceId, "Local MCP Workspace"));
            members.save(new WorkspaceMember(workspace, user, WorkspaceRole.OWNER));
        });
        authorization.setClientEnabled(clientId, true);
        authorization.updateWorkspacePolicy(workspaceId, true, Set.of(McpScope.READ));
        when(fetcher.fetch(clientId)).thenReturn(("""
                {"client_id":"%s","client_name":"Local unverified MCP fixture",
                 "redirect_uris":["%s"],"token_endpoint_auth_method":"none",
                 "grant_types":["authorization_code","refresh_token"],"response_types":["code"]}
                """).formatted(clientId, REGISTERED_REDIRECT).getBytes(StandardCharsets.UTF_8));

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String baseUrl = "http://127.0.0.1:" + port;
        long discoveryStarted = System.nanoTime();
        HttpResponse<String> discovery = send(client, HttpRequest.newBuilder(
                URI.create(baseUrl + "/.well-known/oauth-authorization-server")).GET().build());
        long discoveryMillis = elapsedMillis(discoveryStarted);
        assertThat(discovery.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(discovery.body()).path("authorization_endpoint").asText())
                .isEqualTo("https://access.local.test/oauth2/authorize");

        String webToken = jwt.generateAccessToken(userId, email);
        Issued current = issue(client, baseUrl, userId, workspaceId, clientId, webToken, "initial");
        writeState(baseUrl, userId, workspaceId, email, clientId, webToken, current,
                discoveryMillis, current.tokenMillis());

        await("remove-member");
        tx.executeWithoutResult(status -> members.deleteById(new WorkspaceMemberId(workspaceId, userId)));
        ack("remove-member");

        await("restore-member");
        tx.executeWithoutResult(status -> members.save(new WorkspaceMember(
                workspaces.findById(workspaceId).orElseThrow(), users.findById(userId).orElseThrow(),
                WorkspaceRole.OWNER)));
        ack("restore-member");

        await("policy-cycle");
        assertThat(updatePolicy(client, baseUrl, webToken, workspaceId, false)).isEqualTo(200);
        assertThat(updatePolicy(client, baseUrl, webToken, workspaceId, true)).isEqualTo(200);
        ack("policy-cycle");

        await("reconnect");
        current = issue(client, baseUrl, userId, workspaceId, clientId, webToken, "reconnect");
        writeState(baseUrl, userId, workspaceId, email, clientId, webToken, current,
                discoveryMillis, current.tokenMillis());
        ack("reconnect");

        await("refresh-reuse");
        HttpResponse<String> rotated = token(client, baseUrl, clientId, "refresh_token", null, null,
                current.refreshToken(), null);
        assertThat(rotated.statusCode()).isEqualTo(200);
        JsonNode rotatedBody = mapper.readTree(rotated.body());
        HttpResponse<String> reuse = token(client, baseUrl, clientId, "refresh_token", null, null,
                current.refreshToken(), null);
        assertThat(reuse.statusCode()).isEqualTo(400);
        writeJson(runtimeDir().resolve("refresh-reuse-result.json"), mapper.createObjectNode()
                .put("rotation_status", rotated.statusCode()).put("reuse_status", reuse.statusCode())
                .put("rotated_access_token", rotatedBody.path("access_token").asText()));
        ack("refresh-reuse");

        await("reconnect-after-reuse");
        current = issue(client, baseUrl, userId, workspaceId, clientId, webToken, "after-reuse");
        writeState(baseUrl, userId, workspaceId, email, clientId, webToken, current,
                discoveryMillis, current.tokenMillis());
        ack("reconnect-after-reuse");

        await("disconnect");
        HttpResponse<String> disconnect = send(client, HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/mcp/connections/" + current.grantId()))
                .header("Authorization", "Bearer " + webToken).DELETE().build());
        assertThat(disconnect.statusCode()).isEqualTo(204);
        ack("disconnect");

        await("done");
        ack("done");
    }

    private Issued issue(HttpClient client, String baseUrl, String userId, String workspaceId,
                         String clientId, String webToken, String seed) throws Exception {
        String verifier = verifier(seed);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String query = "?response_type=code&client_id=" + form(clientId)
                + "&redirect_uri=" + form(ACTUAL_REDIRECT) + "&scope=read&state=" + form(seed)
                + "&code_challenge=" + form(challenge) + "&code_challenge_method=S256&resource=" + form(RESOURCE);
        HttpResponse<String> preview = send(client, HttpRequest.newBuilder(
                        URI.create(baseUrl + "/oauth2/authorize" + query))
                .header("Authorization", "Bearer " + webToken).header("Accept", "application/json").GET().build());
        assertThat(preview.statusCode()).isEqualTo(200);
        JsonNode previewBody = mapper.readTree(preview.body());
        assertThat(previewBody.path("workspaces").findValuesAsText("workspace_id")).contains(workspaceId);
        String csrf = preview.headers().firstValue("X-CSRF-TOKEN").orElseThrow();
        HttpResponse<String> decision = send(client, HttpRequest.newBuilder(
                        URI.create(baseUrl + "/oauth2/authorize/" + previewBody.path("pending_id").asText()))
                .header("Authorization", "Bearer " + webToken).header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"decision\":\"APPROVE\",\"workspace_id\":\"" + workspaceId + "\"}"))
                .build());
        assertThat(decision.statusCode()).isEqualTo(200);
        JsonNode decisionBody = mapper.readTree(decision.body());
        String code = query(URI.create(decisionBody.path("redirect_uri").asText()), "code");
        long started = System.nanoTime();
        HttpResponse<String> token = token(client, baseUrl, clientId, "authorization_code", code,
                verifier, null, ACTUAL_REDIRECT);
        long tokenMillis = elapsedMillis(started);
        assertThat(token.statusCode()).isEqualTo(200);
        JsonNode tokenBody = mapper.readTree(token.body());
        return new Issued(tokenBody.path("access_token").asText(), tokenBody.path("refresh_token").asText(),
                decisionBody.path("grant_id").asText(), tokenMillis);
    }

    private HttpResponse<String> token(HttpClient client, String baseUrl, String clientId, String grantType,
                                       String code, String verifier, String refresh, String redirect) throws Exception {
        StringBuilder body = new StringBuilder("grant_type=").append(form(grantType))
                .append("&client_id=").append(form(clientId)).append("&resource=").append(form(RESOURCE));
        if (code != null) body.append("&code=").append(form(code));
        if (verifier != null) body.append("&code_verifier=").append(form(verifier));
        if (refresh != null) body.append("&refresh_token=").append(form(refresh));
        if (redirect != null) body.append("&redirect_uri=").append(form(redirect));
        return send(client, HttpRequest.newBuilder(URI.create(baseUrl + "/oauth2/token"))
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }

    private int updatePolicy(HttpClient client, String baseUrl, String webToken,
                             String workspaceId, boolean enabled) throws Exception {
        return send(client, HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/workspaces/" + workspaceId + "/mcp-policy"))
                .header("Authorization", "Bearer " + webToken)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"enabled\":" + enabled + ",\"allowed_scopes\":[\"read\"]}"))
                .build()).statusCode();
    }

    private void writeState(String baseUrl, String userId, String workspaceId, String email,
                            String clientId, String webToken, Issued issued,
                            long discoveryMillis, long tokenMillis) throws Exception {
        ObjectNode state = mapper.createObjectNode();
        state.put("access_base_url", baseUrl).put("resource", RESOURCE)
                .put("authorization_server", "https://access.local.test")
                .put("user_id", userId).put("workspace_id", workspaceId).put("email", email)
                .put("password", PASSWORD).put("client_id", clientId).put("web_token", webToken)
                .put("mcp_access_token", issued.accessToken()).put("refresh_token", issued.refreshToken())
                .put("grant_id", issued.grantId()).put("public_key", pem("PUBLIC KEY", RSA.getPublic().getEncoded()))
                .put("discovery_duration_ms", discoveryMillis).put("token_duration_ms", tokenMillis);
        state.put("browser_authorization_path", "/oauth2/authorize?response_type=code&client_id=" + form(clientId)
                + "&redirect_uri=" + form(ACTUAL_REDIRECT) + "&scope=read&state=browser-state"
                + "&code_challenge=" + form(challenge("browser-verifier"))
                + "&code_challenge_method=S256&resource=" + form(RESOURCE));
        writeJson(runtimeDir().resolve("access-runtime.json"), state);
    }

    private void writeJson(Path path, JsonNode value) throws Exception {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        try { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static void clearControlFiles() throws Exception {
        if (!Files.exists(runtimeDir())) return;
        try (var files = Files.list(runtimeDir())) {
            files.filter(path -> path.getFileName().toString().startsWith("command-")
                    || path.getFileName().toString().startsWith("ack-"))
                    .forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception e) { throw new RuntimeException(e); } });
        }
        Files.deleteIfExists(runtimeDir().resolve("access-runtime.json"));
    }

    private static void await(String name) throws Exception {
        Path command = runtimeDir().resolve("command-" + name);
        long deadline = System.nanoTime() + Duration.ofMinutes(15).toNanos();
        while (!Files.exists(command) && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(command).exists();
    }

    private static void ack(String name) throws Exception {
        Files.writeString(runtimeDir().resolve("ack-" + name), "ok\n");
    }

    private static Path runtimeDir() {
        return Path.of(System.getenv(RUNTIME_DIR_ENV)).toAbsolutePath().normalize();
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String verifier(String seed) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("0123456789abcdef0123456789abcdef" + seed).getBytes(StandardCharsets.US_ASCII));
    }

    private static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String query(URI uri, String name) {
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            if (pair[0].equals(name)) return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        throw new AssertionError("missing query parameter " + name);
    }

    private static String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    private static String pem(String type, byte[] value) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(value)
                + "\n-----END " + type + "-----";
    }

    private record Issued(String accessToken, String refreshToken, String grantId, long tokenMillis) {}
}
