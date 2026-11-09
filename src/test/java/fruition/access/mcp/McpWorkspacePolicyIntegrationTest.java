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
import fruition.access.mcp.dto.McpWorkspacePolicyRequest;
import fruition.access.mcp.repository.McpGrantRepository;
import fruition.access.mcp.repository.McpWorkspacePolicyRepository;
import fruition.access.mcp.service.McpAuthorizationService;
import fruition.access.mcp.service.McpWorkspacePolicyManagementService;
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
import org.springframework.http.MediaType;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class McpWorkspacePolicyIntegrationTest {
    private static final String MCP_RESOURCE = "https://mcp.test.example/mcp";
    private static final KeyPair RSA = rsa();

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry properties) {
        properties.add("app.jwt.secret", () -> "");
        properties.add("app.jwt.public-key", () -> pem("PUBLIC KEY", RSA.getPublic().getEncoded()));
        properties.add("app.jwt.private-key", () -> pem("PRIVATE KEY", RSA.getPrivate().getEncoded()));
        properties.add("app.jwt.key-id", () -> "mcp-policy-test-key");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider jwt;
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @Autowired McpWorkspacePolicyRepository policies;
    @Autowired McpGrantRepository grants;
    @Autowired McpAuthorizationService authorization;
    @Autowired McpWorkspacePolicyManagementService management;
    @Autowired WorkspaceMemberService memberService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DocumentInternalClient documentClient;
    @MockitoBean AiInternalClient aiClient;

    @Test
    void securityChainAllowsOnlyWebJwtAndGetReturnsNonMutatingDisabledDefault() throws Exception {
        Fixture fixture = fixture(false);
        String mcpToken = jwt.generateMcpAccessToken(fixture.ownerId(), fixture.workspaceId(),
                "https://client.example/policy-security", UUID.randomUUID(), 1, Set.of("read"), MCP_RESOURCE,
                Duration.ofMinutes(2));

        mvc.perform(get(url(fixture.workspaceId()))).andExpect(status().isUnauthorized());
        mvc.perform(get(url(fixture.workspaceId())).header(HttpHeaders.AUTHORIZATION, bearer(mcpToken)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(url(fixture.workspaceId()))
                        .cookie(new Cookie("fruition_refresh_token", "cookie-only")))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(url(fixture.workspaceId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.workspace_id").value(fixture.workspaceId()))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.allowed_scopes.length()").value(0))
                .andExpect(jsonPath("$.generation").isEmpty())
                .andExpect(jsonPath("$.updated_at").isEmpty());
        assertThat(policies.findById(fixture.workspaceId())).isEmpty();

        mvc.perform(put(url(fixture.workspaceId())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"allowed_scopes\":[]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(url(fixture.workspaceId())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"allowed_scopes\":[]}")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mcpToken)))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(url(fixture.workspaceId())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"allowed_scopes\":[]}")
                        .cookie(new Cookie("fruition_refresh_token", "cookie-only")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void currentOwnerMemberForeignMissingAndDeletedBoundariesApplyToReadsAndWrites() throws Exception {
        Fixture fixture = fixture(false);
        Fixture foreign = fixture(false);
        String body = "{\"enabled\":true,\"allowed_scopes\":[\"read\"]}";

        for (var request : List.of(
                get(url(fixture.workspaceId())),
                put(url(fixture.workspaceId())).contentType(MediaType.APPLICATION_JSON).content(body))) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(fixture.memberToken())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("WORKSPACE_ACCESS_DENIED"));
        }
        mvc.perform(get(url(fixture.workspaceId())).header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.memberToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.message").value("OWNER permission is required to manage MCP policies."));
        for (var request : List.of(
                get(url(fixture.workspaceId())),
                put(url(fixture.workspaceId())).contentType(MediaType.APPLICATION_JSON).content(body))) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(foreign.ownerToken())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
        }

        String missing = "ws_missing_" + UUID.randomUUID().toString().replace("-", "");
        mvc.perform(get(url(missing)).header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
        mvc.perform(put(url(missing)).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));

        tx.executeWithoutResult(status -> workspaces.findById(fixture.workspaceId()).orElseThrow()
                .softDelete(fixture.ownerId(), Instant.now()));
        mvc.perform(get(url(fixture.workspaceId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                .andExpect(status().isNotFound());
        mvc.perform(put(url(fixture.workspaceId())).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void putRejectsMissingNullWrongTypesUnknownScopesDuplicatesAndUnknownFields() throws Exception {
        Fixture fixture = fixture(false);
        List<String> invalid = List.of(
                "{}",
                "[]",
                "{\"enabled\":null,\"allowed_scopes\":[]}",
                "{\"enabled\":\"true\",\"allowed_scopes\":[]}",
                "{\"enabled\":1,\"allowed_scopes\":[]}",
                "{\"enabled\":true}",
                "{\"enabled\":true,\"allowed_scopes\":null}",
                "{\"enabled\":true,\"allowed_scopes\":\"read\"}",
                "{\"enabled\":true,\"allowed_scopes\":{}}",
                "{\"enabled\":true,\"allowed_scopes\":[null]}",
                "{\"enabled\":true,\"allowed_scopes\":[\"unknown\"]}",
                "{\"enabled\":true,\"allowed_scopes\":[\"read\",\"read\"]}",
                "{\"enabled\":true,\"allowed_scopes\":[],\"generation\":7}");

        for (String body : invalid) {
            mvc.perform(put(url(fixture.workspaceId()))
                            .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken()))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertThat(policies.findById(fixture.workspaceId())).isEmpty();
    }

    @Test
    void replaceIsIdempotentAndChangesRevokeOldGenerationsWithoutRevivalOrBroadening() throws Exception {
        Fixture fixture = fixture(false);
        String clientId = "https://client.example/" + fixture.workspaceId();
        authorization.setClientEnabled(clientId, true);

        MvcResult enabled = putPolicy(fixture, true, "read", "write_propose")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.allowed_scopes[0]").value("read"))
                .andExpect(jsonPath("$.allowed_scopes[1]").value("write_propose"))
                .andReturn();
        assertThat(json.readTree(enabled.getResponse().getContentAsByteArray()).path("updated_at").isTextual())
                .isTrue();
        McpGrant readGrant = authorization.createGrant(
                fixture.memberId(), fixture.workspaceId(), clientId, Set.of(McpScope.READ));

        putPolicy(fixture, true, "read", "write_propose")
                .andExpect(status().isOk()).andExpect(jsonPath("$.generation").value(2));
        assertThat(grants.findById(readGrant.getGrantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.ACTIVE);

        putPolicy(fixture, true, "write_propose")
                .andExpect(status().isOk()).andExpect(jsonPath("$.generation").value(3));
        McpGrant reduced = grants.findById(readGrant.getGrantId()).orElseThrow();
        assertThat(reduced.getStatus()).isEqualTo(McpGrantStatus.ADMIN_REVOKED);
        assertThat(reduced.getRevocationReason()).isEqualTo(McpGrantRevocationReason.ADMIN_GRANT_REVOKE);
        assertThatThrownBy(() -> authorization.createGrant(
                fixture.memberId(), fixture.workspaceId(), clientId, Set.of(McpScope.READ)))
                .isInstanceOf(IllegalArgumentException.class);

        McpGrant writeGrant = authorization.createGrant(
                fixture.memberId(), fixture.workspaceId(), clientId, Set.of(McpScope.WRITE_PROPOSE));
        putPolicy(fixture, false, "write_propose")
                .andExpect(status().isOk()).andExpect(jsonPath("$.generation").value(4));
        assertThat(grants.findById(writeGrant.getGrantId()).orElseThrow().getRevocationReason())
                .isEqualTo(McpGrantRevocationReason.WORKSPACE_POLICY_DISABLED);
        putPolicy(fixture, true, "write_propose")
                .andExpect(status().isOk()).andExpect(jsonPath("$.generation").value(5));
        assertThat(grants.findById(writeGrant.getGrantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.ADMIN_REVOKED);

        McpAuthorizationResponse oldAuthorization = authorization.authorize(writeGrant.getGrantId(),
                new McpAuthorizationRequest(
                        new McpAuthorizationRequest.TokenContext(fixture.memberId(), fixture.workspaceId(), clientId,
                                3L, Set.of(McpScope.WRITE_PROPOSE)),
                        new McpAuthorizationRequest.Action(McpAuthorizationRequest.Phase.TOOL_CALL,
                                Set.of(McpScope.WRITE_PROPOSE), Set.of(),
                                McpAuthorizationRequest.ExecutionBasis.ACTIVE_GRANT, null)));
        assertThat(oldAuthorization.decision()).isEqualTo(McpAuthorizationResponse.Decision.DENY);
        McpGrant current = authorization.createGrant(
                fixture.memberId(), fixture.workspaceId(), clientId, Set.of(McpScope.WRITE_PROPOSE));
        assertThat(current.getPolicyGeneration()).isEqualTo(5);
    }

    @Test
    void openApiMatchesTheStrictRequestAndNullableAbsentMetadataWireContract() throws Exception {
        JsonNode spec = json.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        JsonNode path = spec.at("/paths/~1api~1workspaces~1{workspace_id}~1mcp-policy");
        assertThat(path.has("get")).isTrue();
        assertThat(path.has("put")).isTrue();

        JsonNode request = spec.at("/components/schemas/McpWorkspacePolicyRequest");
        assertThat(request.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("enabled", "allowed_scopes");
        assertThat(request.path("additionalProperties").asBoolean()).isFalse();
        assertThat(request.at("/properties/enabled/type").asText()).isEqualTo("boolean");
        assertThat(request.at("/properties/allowed_scopes/type").asText()).isEqualTo("array");

        JsonNode response = spec.at("/components/schemas/McpWorkspacePolicyResponse");
        assertThat(response.path("required")).extracting(JsonNode::asText).containsExactlyInAnyOrder(
                "workspace_id", "enabled", "allowed_scopes", "generation", "updated_at");
        assertThat(response.at("/properties/generation/type")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("integer", "null");
        assertThat(response.at("/properties/updated_at/type")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("string", "null");
    }

    @Test
    void ownerDemotionSerializesAfterAuthorizedPolicyUpdateAndBlocksLaterUpdates() throws Exception {
        Fixture fixture = fixture(true);
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch demotionTransactionStarted = new CountDownLatch(1);
        AtomicInteger policyPid = new AtomicInteger();
        AtomicInteger demotionPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        System.out.println("database_version=" + jdbc.queryForObject("SELECT version()", String.class));
        try {
            Future<Long> updating = executor.submit(() -> tx.execute(status -> {
                policyPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                long generation = management.update(fixture.ownerId(), fixture.workspaceId(),
                        new McpWorkspacePolicyRequest(true, Set.of(McpScope.READ))).generation();
                updated.countDown();
                await(release);
                return generation;
            }));
            assertThat(updated.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> demoting = executor.submit(() -> tx.executeWithoutResult(status -> {
                demotionPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                demotionTransactionStarted.countDown();
                memberService.changeRole(fixture.secondOwnerId(), fixture.workspaceId(), fixture.ownerId(),
                        new WorkspaceMemberRoleUpdateRequest(WorkspaceRole.MEMBER, null));
            }));
            assertThat(demotionTransactionStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLock(demoting, demotionPid.get(), policyPid.get());
            release.countDown();

            assertThat(updating.get(10, TimeUnit.SECONDS)).isEqualTo(2L);
            demoting.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(members.findActiveMember(fixture.workspaceId(), fixture.ownerId()).orElseThrow().getRole())
                .isEqualTo(WorkspaceRole.MEMBER);
        mvc.perform(put(url(fixture.workspaceId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"allowed_scopes\":[]}"))
                .andExpect(status().isForbidden());
        assertThat(policies.findById(fixture.workspaceId()).orElseThrow().getGeneration()).isEqualTo(2);
        assertThat(policies.findById(fixture.workspaceId()).orElseThrow().isEnabled()).isTrue();
    }

    private org.springframework.test.web.servlet.ResultActions putPolicy(
            Fixture fixture, boolean enabled, String... scopes) throws Exception {
        String body = json.writeValueAsString(new PolicyBody(enabled, List.of(scopes)));
        return mvc.perform(put(url(fixture.workspaceId()))
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.ownerToken()))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Fixture fixture(boolean secondOwner) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String ownerId = "user_owner_" + suffix;
        String memberId = "user_member_" + suffix;
        String secondOwnerId = secondOwner ? "user_owner2_" + suffix : null;
        String workspaceId = "ws_" + suffix;
        tx.executeWithoutResult(status -> {
            User owner = users.save(user(ownerId));
            User member = users.save(user(memberId));
            User otherOwner = secondOwner ? users.save(user(secondOwnerId)) : null;
            Workspace workspace = workspaces.save(new Workspace(workspaceId, "MCP Policy Test"));
            members.save(new WorkspaceMember(workspace, owner, WorkspaceRole.OWNER));
            members.save(new WorkspaceMember(workspace, member, WorkspaceRole.MEMBER));
            if (otherOwner != null) {
                members.save(new WorkspaceMember(workspace, otherOwner, WorkspaceRole.OWNER));
            }
        });
        return new Fixture(ownerId, memberId, secondOwnerId, workspaceId,
                jwt.generateAccessToken(ownerId, ownerId + "@example.test"),
                jwt.generateAccessToken(memberId, memberId + "@example.test"));
    }

    private static User user(String id) {
        return new User(id, id + "@example.test", User.PROVIDER_LOCAL, "MCP Policy Test", null);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to finish policy transaction");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private void awaitDatabaseLock(Future<?> demoting, int demotionPid, int policyPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<String> blockers = jdbc.query("""
                    SELECT array_to_string(pg_blocking_pids(waiter.pid), ',')
                    FROM pg_stat_activity waiter
                    WHERE waiter.datname=current_database()
                      AND waiter.pid=?
                      AND waiter.wait_event_type='Lock'
                      AND ? = ANY (pg_blocking_pids(waiter.pid))
                    """, (resultSet, row) -> resultSet.getString(1), demotionPid, policyPid);
            if (!blockers.isEmpty()) {
                System.out.printf("policy_lock_wait demotion_pid=%d wait_event_type=Lock policy_pid=%d blockers=%s%n",
                        demotionPid, policyPid, blockers.getFirst());
                return;
            }
            if (demoting.isDone()) {
                throw new AssertionError("demotion completed before waiting on the policy transaction lock");
            }
            Thread.sleep(10);
        }
        throw new AssertionError("demotion did not reach a PostgreSQL Lock wait blocked by the policy transaction");
    }

    private static String url(String workspaceId) {
        return "/api/workspaces/" + workspaceId + "/mcp-policy";
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
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----";
    }

    private record Fixture(String ownerId, String memberId, String secondOwnerId, String workspaceId,
                           String ownerToken, String memberToken) {}

    private record PolicyBody(boolean enabled, List<String> allowed_scopes) {}
}
