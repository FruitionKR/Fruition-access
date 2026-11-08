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
import fruition.access.mcp.service.McpWorkspaceConnectionManagementService;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.dto.WorkspaceMemberRoleUpdateRequest;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import fruition.access.workspace.service.AiInternalClient;
import fruition.access.workspace.service.DocumentInternalClient;
import fruition.access.workspace.service.WorkspaceMemberService;
import fruition.shared.security.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class McpWorkspaceConnectionIntegrationTest {
    private static final String MCP_RESOURCE = "https://mcp.test.example/mcp";
    private static final Set<McpScope> READ = Set.of(McpScope.READ);
    private static final KeyPair RSA = rsa();

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry properties) {
        properties.add("app.jwt.secret", () -> "");
        properties.add("app.jwt.public-key", () -> pem("PUBLIC KEY", RSA.getPublic().getEncoded()));
        properties.add("app.jwt.private-key", () -> pem("PRIVATE KEY", RSA.getPrivate().getEncoded()));
        properties.add("app.jwt.key-id", () -> "mcp-workspace-connection-test-key");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider jwt;
    @Autowired McpAuthorizationService authorization;
    @Autowired McpWorkspaceConnectionManagementService management;
    @Autowired McpGrantRepository grants;
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @Autowired WorkspaceMemberService memberService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DocumentInternalClient documentClient;
    @MockitoBean AiInternalClient aiClient;

    @Test
    void webBearerAndFreshOwnerAreRequiredEvenWhenPolicyIsDisabled() throws Exception {
        Fixture fixture = fixture(false);
        Fixture foreign = fixture(false);
        String mcpToken = jwt.generateMcpAccessToken(fixture.memberId(), fixture.workspaceId(), fixture.clientId(),
                fixture.grant().getGrantId(), fixture.generation(), Set.of("read"), MCP_RESOURCE,
                Duration.ofMinutes(2));

        mvc.perform(get(url(fixture.workspaceId()))).andExpect(status().isUnauthorized());
        mvc.perform(get(url(fixture.workspaceId())).header(HttpHeaders.AUTHORIZATION, bearer(mcpToken)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(url(fixture.workspaceId())).cookie(new Cookie("fruition_refresh_token", "cookie-only")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(url(fixture.workspaceId())).header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.memberToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_ACCESS_DENIED"))
                .andExpect(jsonPath("$.error.message")
                        .value("OWNER permission is required to manage MCP connections."));
        mvc.perform(get(url(fixture.workspaceId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(foreign.ownerToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));

        authorization.updateWorkspacePolicy(fixture.workspaceId(), false, READ);
        mvc.perform(get(url(fixture.workspaceId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.connections[0].grant_id").value(fixture.grant().getGrantId().toString()));
        revoke(fixture, fixture.grant());

        tx.executeWithoutResult(ignored -> members.findActiveMember(fixture.workspaceId(), fixture.ownerId())
                .orElseThrow().changeRole(WorkspaceRole.MEMBER));
        mvc.perform(get(url(fixture.workspaceId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void listIsWorkspaceScopedStablyPagedAndUsesTheSeparateAdminShape() throws Exception {
        Fixture fixture = fixture(false);
        McpGrant older = grant(fixture, Instant.parse("2026-01-02T00:00:00Z"));
        McpGrant oldest = grant(fixture, Instant.parse("2026-01-01T00:00:00Z"));
        Fixture foreign = fixture(false);

        MvcResult firstPage = mvc.perform(get(url(fixture.workspaceId())).param("page", "0").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.has_next").value(true))
                .andExpect(jsonPath("$.connections[0].grant_id").value(fixture.grant().getGrantId().toString()))
                .andExpect(jsonPath("$.connections[1].grant_id").value(older.getGrantId().toString()))
                .andExpect(jsonPath("$.connections[0].user_id").value(fixture.memberId()))
                .andReturn();
        JsonNode connection = json.readTree(firstPage.getResponse().getContentAsByteArray())
                .path("connections").get(0);
        assertThat(connection.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("grant_id", "user_id", "workspace_id", "client_id", "scopes",
                        "status", "revocation_reason", "created_at", "revoked_at");
        assertThat(firstPage.getResponse().getContentAsString())
                .doesNotContain(foreign.workspaceId(), "access_token", "refresh_token", "secret", "last_used");

        mvc.perform(get(url(fixture.workspaceId())).param("page", "1").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_next").value(false))
                .andExpect(jsonPath("$.connections[0].grant_id").value(oldest.getGrantId().toString()))
                .andExpect(jsonPath("$.connections.length()").value(1));
    }

    @Test
    void invalidPagingAndGrantIdsAreRejected() throws Exception {
        Fixture fixture = fixture(false);
        for (String suffix : List.of("?page=-1", "?size=0", "?size=101", "?page=not-a-number",
                "?page=2147483647&size=100")) {
            mvc.perform(get(url(fixture.workspaceId()) + suffix)
                            .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                    .andExpect(status().isBadRequest());
        }
        for (String grantId : List.of("not-a-uuid", "00000000-0000-1000-8000-000000000001")) {
            mvc.perform(delete(url(fixture.workspaceId()) + "/{grant_id}", grantId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void foreignAndMissingGrantAreIdentical404AndDoNotMutate() throws Exception {
        Fixture fixture = fixture(false);
        Fixture foreign = fixture(false);

        MvcResult foreignResult = mvc.perform(delete(url(fixture.workspaceId()) + "/{grant_id}",
                        foreign.grant().getGrantId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult missingResult = mvc.perform(delete(url(fixture.workspaceId()) + "/{grant_id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                .andExpect(status().isNotFound()).andReturn();

        assertThat(foreignResult.getResponse().getContentAsByteArray())
                .isEqualTo(missingResult.getResponse().getContentAsByteArray());
        assertThat(grants.findById(foreign.grant().getGrantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.ACTIVE);
        mvc.perform(delete(url(fixture.workspaceId()) + "/{grant_id}", fixture.grant().getGrantId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.memberToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokeIsIdempotentEscalatesUserDisconnectPreservesStrongerReasonsAndDeniesCurrentUse() throws Exception {
        Fixture fixture = fixture(false);

        revoke(fixture, fixture.grant());
        McpGrant activeResult = grants.findById(fixture.grant().getGrantId()).orElseThrow();
        assertThat(activeResult.getRevocationReason()).isEqualTo(McpGrantRevocationReason.ADMIN_GRANT_REVOKE);
        Instant revokedAt = activeResult.getRevokedAt();
        revoke(fixture, fixture.grant());
        assertThat(grants.findById(fixture.grant().getGrantId()).orElseThrow().getRevokedAt()).isEqualTo(revokedAt);
        assertThat(authorize(fixture, fixture.grant()).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.GRANT_INACTIVE);

        McpGrant disconnected = grant(fixture, Instant.now().minusSeconds(3));
        assertThat(authorization.disconnectOwnGrant(fixture.memberId(), disconnected.getGrantId())).isTrue();
        revoke(fixture, disconnected);
        assertThat(grants.findById(disconnected.getGrantId()).orElseThrow().getRevocationReason())
                .isEqualTo(McpGrantRevocationReason.ADMIN_GRANT_REVOKE);

        McpGrant security = grant(fixture, Instant.now().minusSeconds(2));
        authorization.revokeGrant(security.getGrantId(), McpGrantRevocationReason.REFRESH_REUSE);
        revoke(fixture, security);
        assertThat(grants.findById(security.getGrantId()).orElseThrow().getRevocationReason())
                .isEqualTo(McpGrantRevocationReason.REFRESH_REUSE);

        McpGrant purged = grant(fixture, Instant.now().minusSeconds(1));
        authorization.revokeGrant(purged.getGrantId(), McpGrantRevocationReason.USER_PURGED);
        revoke(fixture, purged);
        assertThat(grants.findById(purged.getGrantId()).orElseThrow().getRevocationReason())
                .isEqualTo(McpGrantRevocationReason.USER_PURGED);
    }

    @Test
    void ownerDemotionWaitsForAdministrativeRevokeCommitAndBlocksLaterRevokes() throws Exception {
        Fixture fixture = fixture(true);
        CountDownLatch revoked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch demotionStarted = new CountDownLatch(1);
        AtomicInteger revokePid = new AtomicInteger();
        AtomicInteger demotionPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> revoking = executor.submit(() -> tx.execute(ignored -> {
                revokePid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                boolean result = management.revoke(fixture.ownerId(), fixture.workspaceId(),
                        fixture.grant().getGrantId());
                revoked.countDown();
                await(release);
                return result;
            }));
            assertThat(revoked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> demoting = executor.submit(() -> tx.executeWithoutResult(ignored -> {
                demotionPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                demotionStarted.countDown();
                memberService.changeRole(fixture.secondOwnerId(), fixture.workspaceId(), fixture.ownerId(),
                        new WorkspaceMemberRoleUpdateRequest(WorkspaceRole.MEMBER, null));
            }));
            assertThat(demotionStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLock(demoting, demotionPid.get(), revokePid.get());
            release.countDown();

            assertThat(revoking.get(10, TimeUnit.SECONDS)).isTrue();
            demoting.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(grants.findById(fixture.grant().getGrantId()).orElseThrow().getRevocationReason())
                .isEqualTo(McpGrantRevocationReason.ADMIN_GRANT_REVOKE);
        mvc.perform(delete(url(fixture.workspaceId()) + "/{grant_id}", fixture.grant().getGrantId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void openApiExposesTheAdminPathAndKeepsTheOwnConnectionShapeFrozen() throws Exception {
        JsonNode spec = json.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(spec.at("/paths/~1api~1workspaces~1{workspace_id}~1mcp-connections").has("get")).isTrue();
        assertThat(spec.at("/paths/~1api~1workspaces~1{workspace_id}~1mcp-connections~1{grant_id}")
                .has("delete")).isTrue();

        JsonNode adminConnection = spec.at("/components/schemas/WorkspaceMcpConnection");
        assertThat(adminConnection.path("required")).extracting(JsonNode::asText).containsExactlyInAnyOrder(
                "grant_id", "user_id", "workspace_id", "client_id", "scopes", "status",
                "revocation_reason", "created_at", "revoked_at");
        assertThat(spec.at("/components/schemas/Connection/properties").has("user_id")).isFalse();
    }

    private void revoke(Fixture fixture, McpGrant grant) throws Exception {
        mvc.perform(delete(url(fixture.workspaceId()) + "/{grant_id}", grant.getGrantId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                .andExpect(status().isNoContent());
    }

    private McpAuthorizationResponse authorize(Fixture fixture, McpGrant grant) {
        return authorization.authorize(grant.getGrantId(), new McpAuthorizationRequest(
                new McpAuthorizationRequest.TokenContext(fixture.memberId(), fixture.workspaceId(),
                        fixture.clientId(), fixture.generation(), READ),
                new McpAuthorizationRequest.Action(McpAuthorizationRequest.Phase.TOOL_CALL, READ, Set.of(),
                        McpAuthorizationRequest.ExecutionBasis.ACTIVE_GRANT, null)));
    }

    private Fixture fixture(boolean secondOwner) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String ownerId = "user_owner_" + suffix;
        String memberId = "user_member_" + suffix;
        String secondOwnerId = secondOwner ? "user_owner2_" + suffix : null;
        String workspaceId = "ws_" + suffix;
        String clientId = "https://client.example/" + suffix;
        tx.executeWithoutResult(ignored -> {
            User owner = users.save(user(ownerId));
            User member = users.save(user(memberId));
            User otherOwner = secondOwner ? users.save(user(secondOwnerId)) : null;
            Workspace workspace = workspaces.save(new Workspace(workspaceId, "MCP Workspace Connection Test"));
            members.save(new WorkspaceMember(workspace, owner, WorkspaceRole.OWNER));
            members.save(new WorkspaceMember(workspace, member, WorkspaceRole.MEMBER));
            if (otherOwner != null) {
                members.save(new WorkspaceMember(workspace, otherOwner, WorkspaceRole.OWNER));
            }
        });
        authorization.setClientEnabled(clientId, true);
        long generation = authorization.updateWorkspacePolicy(workspaceId, true, READ).getGeneration();
        McpGrant grant = authorization.createGrant(memberId, workspaceId, clientId, READ);
        return new Fixture(ownerId, memberId, secondOwnerId, workspaceId, clientId, generation, grant,
                jwt.generateAccessToken(ownerId, ownerId + "@example.test"),
                jwt.generateAccessToken(memberId, memberId + "@example.test"));
    }

    private McpGrant grant(Fixture fixture, Instant createdAt) {
        return grants.saveAndFlush(new McpGrant(UUID.randomUUID(), fixture.memberId(), fixture.workspaceId(),
                fixture.clientId(), fixture.generation(), READ, createdAt));
    }

    private static User user(String id) {
        return new User(id, id + "@example.test", User.PROVIDER_LOCAL, "MCP Workspace Connection Test", null);
    }

    private void awaitDatabaseLock(Future<?> demoting, int demotionPid, int revokePid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<String> blockers = jdbc.query("""
                    SELECT array_to_string(pg_blocking_pids(waiter.pid), ',')
                    FROM pg_stat_activity waiter
                    WHERE waiter.datname=current_database()
                      AND waiter.pid=?
                      AND waiter.wait_event_type='Lock'
                      AND ? = ANY (pg_blocking_pids(waiter.pid))
                    """, (resultSet, row) -> resultSet.getString(1), demotionPid, revokePid);
            if (!blockers.isEmpty()) {
                return;
            }
            if (demoting.isDone()) {
                throw new AssertionError("demotion completed before waiting on the management transaction lock");
            }
            Thread.sleep(10);
        }
        throw new AssertionError("demotion did not reach a PostgreSQL lock wait blocked by management");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to finish management transaction");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static String url(String workspaceId) {
        return "/api/workspaces/" + workspaceId + "/mcp-connections";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
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

    private record Fixture(String ownerId, String memberId, String secondOwnerId, String workspaceId,
                           String clientId, long generation, McpGrant grant, String ownerToken,
                           String memberToken) {}
}
