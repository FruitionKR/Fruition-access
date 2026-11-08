package fruition.access.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.access.mcp.domain.McpGrant;
import fruition.access.mcp.domain.McpGrantRevocationReason;
import fruition.access.mcp.domain.McpGrantStatus;
import fruition.access.mcp.domain.McpScope;
import fruition.access.mcp.dto.McpAuthorizationRequest;
import fruition.access.mcp.dto.McpAuthorizationResponse;
import fruition.access.mcp.repository.McpGrantRepository;
import fruition.access.mcp.service.McpAuthorizationService;
import fruition.access.mcp.service.McpConnectionService;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import fruition.access.workspace.service.AiInternalClient;
import fruition.access.workspace.service.DocumentInternalClient;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class McpConnectionIntegrationTest {
    private static final String MCP_RESOURCE = "https://mcp.test.example/mcp";
    private static final Set<McpScope> READ = Set.of(McpScope.READ);
    private static final KeyPair RSA = rsa();

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry properties) {
        properties.add("app.jwt.secret", () -> "");
        properties.add("app.jwt.public-key", () -> pem("PUBLIC KEY", RSA.getPublic().getEncoded()));
        properties.add("app.jwt.private-key", () -> pem("PRIVATE KEY", RSA.getPrivate().getEncoded()));
        properties.add("app.jwt.key-id", () -> "mcp-connection-test-key");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider jwt;
    @Autowired McpAuthorizationService authorization;
    @Autowired McpConnectionService connections;
    @Autowired McpGrantRepository grants;
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @Autowired TransactionTemplate tx;
    @Autowired DataSource dataSource;
    @MockitoBean DocumentInternalClient documentClient;
    @MockitoBean AiInternalClient aiClient;

    @Test
    void realSecurityChainRequiresWebJwtAndRejectsMcpAudienceJwt() throws Exception {
        Fixture fixture = fixture(WorkspaceRole.MEMBER);
        String mcpToken = jwt.generateMcpAccessToken(fixture.userId(), fixture.workspaceId(), fixture.clientId(),
                fixture.grant().getGrantId(), fixture.generation(), Set.of("read"), MCP_RESOURCE,
                Duration.ofMinutes(2));

        mvc.perform(get("/api/mcp/connections")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/mcp/connections").header(HttpHeaders.AUTHORIZATION, bearer(mcpToken)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/mcp/connections").header(HttpHeaders.AUTHORIZATION, bearer(fixture.webToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connections[0].grant_id").value(fixture.grant().getGrantId().toString()));
    }

    @Test
    void listIsUserScopedStablyPagedAndContainsNoCredentialMetadata() throws Exception {
        Fixture fixture = fixture(WorkspaceRole.MEMBER);
        McpGrant older = grant(fixture, Instant.parse("2026-01-02T00:00:00Z"));
        McpGrant oldest = grant(fixture, Instant.parse("2026-01-01T00:00:00Z"));

        MvcResult firstPage = mvc.perform(get("/api/mcp/connections").param("page", "0").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.webToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.has_next").value(true))
                .andExpect(jsonPath("$.connections[0].grant_id").value(fixture.grant().getGrantId().toString()))
                .andExpect(jsonPath("$.connections[1].grant_id").value(older.getGrantId().toString()))
                .andReturn();
        JsonNode connection = json.readTree(firstPage.getResponse().getContentAsByteArray())
                .path("connections").get(0);
        assertThat(connection.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("grant_id", "workspace_id", "client_id", "scopes", "status",
                        "revocation_reason", "created_at", "revoked_at");
        assertThat(connection.path("revocation_reason").isNull()).isTrue();
        assertThat(connection.path("revoked_at").isNull()).isTrue();

        mvc.perform(get("/api/mcp/connections").param("page", "1").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.webToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_next").value(false))
                .andExpect(jsonPath("$.connections[0].grant_id").value(oldest.getGrantId().toString()))
                .andExpect(jsonPath("$.connections.length()").value(1));
    }

    @Test
    void invalidPagingAndGrantIdsAreRejected() throws Exception {
        Fixture fixture = fixture(WorkspaceRole.MEMBER);
        for (String url : List.of("/api/mcp/connections?page=-1", "/api/mcp/connections?size=0",
                "/api/mcp/connections?size=101", "/api/mcp/connections?page=not-a-number")) {
            mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(fixture.webToken())))
                    .andExpect(status().isBadRequest());
        }
        for (String grantId : List.of("not-a-uuid", "00000000-0000-1000-8000-000000000001")) {
            mvc.perform(delete("/api/mcp/connections/{grant_id}", grantId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(fixture.webToken())))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void directInvalidConnectionErrorsFollowAcceptLanguageAndDefaultToKorean() throws Exception {
        Fixture fixture = fixture(WorkspaceRole.MEMBER);

        mvc.perform(get("/api/mcp/connections?page=-1").header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.webToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("The page or size is not valid."));
        mvc.perform(delete("/api/mcp/connections/{grant_id}", "not-a-uuid").header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.webToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("The grant_id is not in a valid format."));
        mvc.perform(get("/api/mcp/connections?page=-1").header(HttpHeaders.AUTHORIZATION, bearer(fixture.webToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("page 또는 size가 올바르지 않습니다."));
    }

    @Test
    void paginationOffsetBeyondJpaIntegerLimitIsRejectedWhileLargestLegalOffsetIsAccepted() throws Exception {
        Fixture fixture = fixture(WorkspaceRole.MEMBER);

        mvc.perform(get("/api/mcp/connections?page=2147483647&size=100")
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.webToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/mcp/connections?page=107374183")
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.webToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/mcp/connections?page=21474836&size=100")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.webToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connections.length()").value(0));
    }

    @Test
    void generatedConnectionSchemaAllowsNullAndOnlyKnownRevocationReasons() throws Exception {
        JsonNode revocationReason = json.readTree(mvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .at("/components/schemas/Connection/properties/revocation_reason");
        JsonNode oneOf = revocationReason.path("oneOf");

        assertThat(oneOf).hasSize(2);
        assertThat(oneOf.get(0).path("type").asText()).isEqualTo("string");
        assertThat(oneOf.get(0).path("enum")).extracting(JsonNode::asText).containsExactly(
                "USER_DISCONNECT", "ADMIN_GRANT_REVOKE", "WORKSPACE_POLICY_DISABLED", "REFRESH_REUSE",
                "CLIENT_DISABLED", "USER_PURGED", "WORKSPACE_PURGED");
        assertThat(oneOf.get(0).path("enum")).extracting(JsonNode::asText).doesNotContain("NOT_A_REASON");
        assertThat(oneOf.get(1).path("type").asText()).isEqualTo("null");
    }

    @Test
    void workspaceOwnerCannotSeeOrDisconnectAnotherUsersGrantButFormerMemberCanDisconnectOwn() throws Exception {
        Fixture fixture = fixture(WorkspaceRole.MEMBER);
        String ownerId = id("owner");
        User owner = users.save(new User(ownerId, ownerId + "@example.test", User.PROVIDER_LOCAL, "Owner", null));
        members.save(new WorkspaceMember(workspaces.findById(fixture.workspaceId()).orElseThrow(), owner,
                WorkspaceRole.OWNER));
        String ownerToken = jwt.generateAccessToken(ownerId, owner.getEmail());

        mvc.perform(get("/api/mcp/connections").header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.connections.length()").value(0));
        MvcResult foreign = mvc.perform(delete("/api/mcp/connections/{grant_id}", fixture.grant().getGrantId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult missing = mvc.perform(delete("/api/mcp/connections/{grant_id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(foreign.getResponse().getContentAsByteArray())
                .isEqualTo(missing.getResponse().getContentAsByteArray());

        members.deleteById(new fruition.access.workspace.domain.WorkspaceMemberId(
                fixture.workspaceId(), fixture.userId()));
        mvc.perform(delete("/api/mcp/connections/{grant_id}", fixture.grant().getGrantId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.webToken())))
                .andExpect(status().isNoContent());
        assertThat(grants.findById(fixture.grant().getGrantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.USER_DISCONNECTED);
    }

    @Test
    void disconnectBlocksCurrentCallsAndCredentialIssuanceButPreservesPriorApprovedExecution() throws Exception {
        Fixture fixture = fixture(WorkspaceRole.MEMBER);
        mvc.perform(delete("/api/mcp/connections/{grant_id}", fixture.grant().getGrantId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.webToken())))
                .andExpect(status().isNoContent());

        assertThat(authorize(fixture, action(McpAuthorizationRequest.Phase.TOOL_CALL,
                McpAuthorizationRequest.ExecutionBasis.ACTIVE_GRANT, null)).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.GRANT_INACTIVE);
        assertThat(authorize(fixture, action(McpAuthorizationRequest.Phase.OPERATION_BOUNDARY,
                McpAuthorizationRequest.ExecutionBasis.EXPLICIT_WEB_APPROVAL, fixture.generation())).decision())
                .isEqualTo(McpAuthorizationResponse.Decision.ALLOW);
        tx.executeWithoutResult(status -> {
            var context = authorization.lockCredentialContext(fixture.grant().getGrantId(), fixture.userId(),
                    fixture.workspaceId(), fixture.clientId());
            assertThatThrownBy(() -> authorization.requireCredentialIssuance(context, READ, fixture.generation()))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void repeatedDisconnectIsIdempotentAndNeverDowngradesStrongerRevocations() throws Exception {
        Fixture repeated = fixture(WorkspaceRole.MEMBER);
        disconnect(repeated);
        Instant disconnectedAt = grants.findById(repeated.grant().getGrantId()).orElseThrow().getRevokedAt();
        disconnect(repeated);
        assertThat(grants.findById(repeated.grant().getGrantId()).orElseThrow().getRevokedAt())
                .isEqualTo(disconnectedAt);

        Fixture admin = fixture(WorkspaceRole.MEMBER);
        authorization.revokeGrant(admin.grant().getGrantId(), McpGrantRevocationReason.ADMIN_GRANT_REVOKE);
        disconnect(admin);
        assertThat(grants.findById(admin.grant().getGrantId()).orElseThrow().getRevocationReason())
                .isEqualTo(McpGrantRevocationReason.ADMIN_GRANT_REVOKE);

        Fixture security = fixture(WorkspaceRole.MEMBER);
        authorization.revokeGrant(security.grant().getGrantId(), McpGrantRevocationReason.REFRESH_REUSE);
        disconnect(security);
        assertThat(grants.findById(security.grant().getGrantId()).orElseThrow().getRevocationReason())
                .isEqualTo(McpGrantRevocationReason.REFRESH_REUSE);

        Fixture purged = fixture(WorkspaceRole.MEMBER);
        users.deleteById(purged.userId());
        disconnect(purged);
        assertThat(grants.findById(purged.grant().getGrantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.PURGED);
    }

    @Test
    void securityRevocationWhileDisconnectWaitsAtTheClientLockWinsWithoutPostDisconnectIssuance() throws Exception {
        Fixture fixture = fixture(WorkspaceRole.MEMBER);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var statement = holder.prepareStatement(
                    "SELECT client_id FROM mcp_client_policies WHERE client_id=? FOR UPDATE")) {
                statement.setString(1, fixture.clientId());
                statement.executeQuery();
            }
            Future<Boolean> disconnecting = executor.submit(
                    () -> connections.disconnect(fixture.userId(), fixture.grant().getGrantId()));
            assertThatThrownBy(() -> disconnecting.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            Future<?> security = executor.submit(() -> authorization.revokeGrant(
                    fixture.grant().getGrantId(), McpGrantRevocationReason.REFRESH_REUSE));
            security.get(10, TimeUnit.SECONDS);
            holder.commit();
            assertThat(disconnecting.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        McpGrant result = grants.findById(fixture.grant().getGrantId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(McpGrantStatus.SECURITY_REVOKED);
        assertThat(result.getRevocationReason()).isEqualTo(McpGrantRevocationReason.REFRESH_REUSE);
        assertThat(authorize(fixture, action(McpAuthorizationRequest.Phase.OPERATION_BOUNDARY,
                McpAuthorizationRequest.ExecutionBasis.EXPLICIT_WEB_APPROVAL, fixture.generation())).decision())
                .isEqualTo(McpAuthorizationResponse.Decision.DENY);
    }

    private void disconnect(Fixture fixture) throws Exception {
        mvc.perform(delete("/api/mcp/connections/{grant_id}", fixture.grant().getGrantId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.webToken())))
                .andExpect(status().isNoContent());
    }

    private McpAuthorizationResponse authorize(Fixture fixture, McpAuthorizationRequest.Action action) {
        return authorization.authorize(fixture.grant().getGrantId(), new McpAuthorizationRequest(
                new McpAuthorizationRequest.TokenContext(fixture.userId(), fixture.workspaceId(), fixture.clientId(),
                        fixture.generation(), READ), action));
    }

    private static McpAuthorizationRequest.Action action(McpAuthorizationRequest.Phase phase,
                                                          McpAuthorizationRequest.ExecutionBasis basis,
                                                          Long approvedGeneration) {
        return new McpAuthorizationRequest.Action(phase, READ, Set.of(), basis, approvedGeneration);
    }

    private Fixture fixture(WorkspaceRole role) {
        String userId = id("user");
        String workspaceId = id("ws");
        String clientId = "https://client.example/" + UUID.randomUUID();
        User user = users.save(new User(userId, userId + "@example.test", User.PROVIDER_LOCAL, "MCP Test", null));
        Workspace workspace = workspaces.save(new Workspace(workspaceId, "MCP Test"));
        members.save(new WorkspaceMember(workspace, user, role));
        authorization.setClientEnabled(clientId, true);
        long generation = authorization.updateWorkspacePolicy(workspaceId, true, READ).getGeneration();
        McpGrant grant = authorization.createGrant(userId, workspaceId, clientId, READ);
        return new Fixture(userId, workspaceId, clientId, generation, grant,
                jwt.generateAccessToken(userId, user.getEmail()));
    }

    private McpGrant grant(Fixture fixture, Instant createdAt) {
        return grants.saveAndFlush(new McpGrant(UUID.randomUUID(), fixture.userId(), fixture.workspaceId(),
                fixture.clientId(), fixture.generation(), READ, createdAt));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String pem(String type, byte[] value) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(value)
                + "\n-----END " + type + "-----";
    }

    private record Fixture(String userId, String workspaceId, String clientId, long generation,
                           McpGrant grant, String webToken) {}
}
