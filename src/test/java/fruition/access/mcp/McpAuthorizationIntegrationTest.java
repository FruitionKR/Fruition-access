package fruition.access.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.TestcontainersConfiguration;
import fruition.access.cleanup.DataPurgeRequestJob;
import fruition.access.cleanup.ExpiredRecordCleanupJob;
import fruition.access.mcp.domain.McpGrant;
import fruition.access.mcp.domain.McpGrantRevocationReason;
import fruition.access.mcp.domain.McpGrantStatus;
import fruition.access.mcp.domain.McpScope;
import fruition.access.mcp.dto.McpAuthorizationRequest;
import fruition.access.mcp.dto.McpAuthorizationResponse;
import fruition.access.mcp.oauth.McpClientMetadata;
import fruition.access.mcp.oauth.McpClientMetadataResolver;
import fruition.access.mcp.repository.McpClientPolicyRepository;
import fruition.access.mcp.repository.McpGrantRepository;
import fruition.access.mcp.repository.McpWorkspacePolicyRepository;
import fruition.access.mcp.service.McpAuthorizationService;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspacePermission;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import fruition.access.workspace.service.AiInternalClient;
import fruition.access.workspace.service.DocumentInternalClient;
import fruition.access.workspace.service.WorkspaceMemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class McpAuthorizationIntegrationTest {
    private static final Set<McpScope> READ = Set.of(McpScope.READ);
    private static final Set<McpScope> WRITE = Set.of(McpScope.WRITE_PROPOSE);

    @Autowired McpAuthorizationService service;
    @Autowired McpGrantRepository grants;
    @Autowired McpWorkspacePolicyRepository policies;
    @Autowired McpClientPolicyRepository clients;
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource dataSource;
    @Autowired WorkspaceMemberService memberService;
    @Autowired ExpiredRecordCleanupJob expiredCleanup;
    @Autowired DataPurgeRequestJob purgeRequests;
    @Value("${app.internal.callback-token}") String internalToken;
    @MockitoBean DocumentInternalClient documentClient;
    @MockitoBean AiInternalClient aiClient;
    @MockitoBean McpClientMetadataResolver clientMetadata;

    @Test
    void readsMembershipAndPermissionsFromOriginalStoreOnEveryDecision() {
        Fixture fixture = fixture(Set.of(McpScope.READ, McpScope.WIKI_WRITE),
                Set.of(McpScope.READ, McpScope.WIKI_WRITE), Set.of(WorkspacePermission.WIKI_INGEST));
        var write = action(Set.of(McpScope.WIKI_WRITE), Set.of(WorkspacePermission.WIKI_INGEST));

        assertThat(authorize(fixture, write).decision()).isEqualTo(McpAuthorizationResponse.Decision.ALLOW);

        tx.executeWithoutResult(status -> members.findActiveMember(fixture.workspaceId(), fixture.userId())
                .orElseThrow().replacePermissions(Set.of()));
        assertThat(authorize(fixture, write).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.PERMISSION_REVOKED);

        tx.executeWithoutResult(status -> members.deleteById(
                new fruition.access.workspace.domain.WorkspaceMemberId(fixture.workspaceId(), fixture.userId())));
        assertThat(authorize(fixture, action(READ, Set.of())).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.NOT_MEMBER);
    }

    @Test
    void rejectsTokenContextMismatchWithoutLeakingStoredIdentity() {
        Fixture fixture = fixture(READ, READ, Set.of());
        McpAuthorizationRequest mismatched = request(fixture, action(READ, Set.of()), fixture.userId() + "-other",
                fixture.workspaceId(), fixture.clientId(), fixture.generation(), READ);

        McpAuthorizationResponse response = service.authorize(fixture.grant().getGrantId(), mismatched);

        assertThat(response.denyReason()).isEqualTo(McpAuthorizationResponse.DenyReason.TOKEN_CONTEXT_MISMATCH);
        assertThat(response.grant()).isNull();
        assertThat(response.policy()).isNull();
        assertThat(response.currentAccess()).isNull();

        McpAuthorizationResponse unknown = service.authorize(UUID.randomUUID(), request(fixture, action(READ, Set.of())));
        assertThat(unknown.denyReason()).isEqualTo(McpAuthorizationResponse.DenyReason.GRANT_INACTIVE);
        assertThat(unknown.grant()).isNull();
        assertThat(unknown.policy()).isNull();
        assertThat(unknown.currentAccess()).isNull();
    }

    @Test
    void effectiveScopesAreTheIntersectionAndDoNotExpand() {
        Fixture fixture = fixture(Set.of(McpScope.READ, McpScope.WRITE_PROPOSE), READ, Set.of());

        McpAuthorizationResponse response = authorize(fixture,
                action(Set.of(McpScope.WRITE_PROPOSE), Set.of()));

        assertThat(response.denyReason()).isEqualTo(McpAuthorizationResponse.DenyReason.INSUFFICIENT_SCOPE);
        assertThat(response.effectiveScopes()).containsExactly(McpScope.READ);
    }

    @Test
    void policyNoOpsDoNotIncrementAndOffOnInvalidatesOldGrant() {
        Base base = base(Set.of());
        assertThat(service.updateWorkspacePolicy(base.workspaceId(), false, Set.of()).getGeneration()).isEqualTo(1);
        assertThat(service.updateWorkspacePolicy(base.workspaceId(), false, Set.of()).getGeneration()).isEqualTo(1);
        assertThat(service.updateWorkspacePolicy(base.workspaceId(), true, READ).getGeneration()).isEqualTo(2);
        assertThat(service.updateWorkspacePolicy(base.workspaceId(), true, READ).getGeneration()).isEqualTo(2);
        McpGrant old = service.createGrant(base.userId(), base.workspaceId(), base.clientId(), READ);
        service.revokeGrant(old.getGrantId(), McpGrantRevocationReason.USER_DISCONNECT);

        assertThat(service.updateWorkspacePolicy(base.workspaceId(), false, READ).getGeneration()).isEqualTo(3);
        assertThat(grants.findById(old.getGrantId()).orElseThrow().getRevocationReason())
                .isEqualTo(McpGrantRevocationReason.WORKSPACE_POLICY_DISABLED);
        assertThat(service.updateWorkspacePolicy(base.workspaceId(), true, READ).getGeneration()).isEqualTo(4);
        assertThat(grants.findById(old.getGrantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.ADMIN_REVOKED);
        assertThat(service.createGrant(base.userId(), base.workspaceId(), base.clientId(), READ).getPolicyGeneration())
                .isEqualTo(4);
    }

    @Test
    void policyAndGrantRevocationRollbackTogetherOnDatabaseError() {
        Fixture fixture = fixture(READ, READ, Set.of());
        jdbc.execute("""
                CREATE FUNCTION reject_mcp_grant_update_i1_test() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF OLD.grant_id = '%s' THEN RAISE EXCEPTION 'injected grant update failure'; END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(fixture.grant().getGrantId()));
        jdbc.execute("""
                CREATE TRIGGER reject_mcp_grant_update_i1_test
                BEFORE UPDATE ON mcp_grants
                FOR EACH ROW EXECUTE FUNCTION reject_mcp_grant_update_i1_test()
                """);
        try {
            assertThatThrownBy(() -> service.updateWorkspacePolicy(fixture.workspaceId(), false, READ))
                    .isInstanceOf(RuntimeException.class);
            assertThat(policies.findById(fixture.workspaceId()).orElseThrow().isEnabled()).isTrue();
            assertThat(policies.findById(fixture.workspaceId()).orElseThrow().getGeneration())
                    .isEqualTo(fixture.generation());
            assertThat(grants.findById(fixture.grant().getGrantId()).orElseThrow().getStatus())
                    .isEqualTo(McpGrantStatus.ACTIVE);
        } finally {
            jdbc.execute("DROP TRIGGER reject_mcp_grant_update_i1_test ON mcp_grants");
            jdbc.execute("DROP FUNCTION reject_mcp_grant_update_i1_test()");
        }
    }

    @Test
    void disconnectExceptionNeedsExplicitApprovalAndLaterRevocationsTakePrecedence() {
        Fixture fixture = fixture(READ, READ, Set.of());
        UUID grantId = fixture.grant().getGrantId();
        service.revokeGrant(grantId, McpGrantRevocationReason.USER_DISCONNECT);
        var disconnectedAt = grants.findById(grantId).orElseThrow().getRevokedAt();
        service.revokeGrant(grantId, McpGrantRevocationReason.USER_DISCONNECT);
        assertThat(grants.findById(grantId).orElseThrow().getRevokedAt()).isEqualTo(disconnectedAt);

        assertThat(authorize(fixture, action(READ, Set.of())).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.GRANT_INACTIVE);
        assertThat(authorize(fixture, explicitAction(fixture.generation())).decision())
                .isEqualTo(McpAuthorizationResponse.Decision.ALLOW);
        assertThat(authorize(fixture, explicitAction(fixture.generation() + 1)).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.POLICY_GENERATION_MISMATCH);

        service.revokeGrant(grantId, McpGrantRevocationReason.ADMIN_GRANT_REVOKE);
        assertThat(authorize(fixture, explicitAction(fixture.generation())).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.GRANT_INACTIVE);
        service.revokeGrant(grantId, McpGrantRevocationReason.REFRESH_REUSE);
        service.revokeGrant(grantId, McpGrantRevocationReason.ADMIN_GRANT_REVOKE);
        McpGrant securityRevoked = grants.findById(grantId).orElseThrow();
        assertThat(securityRevoked.getStatus()).isEqualTo(McpGrantStatus.SECURITY_REVOKED);
        assertThat(securityRevoked.getRevocationReason()).isEqualTo(McpGrantRevocationReason.REFRESH_REUSE);
    }

    @Test
    void missingPolicyOrClientFailsClosedAndClientOffOnDoesNotResurrect() {
        Fixture missingPolicy = fixture(READ, READ, Set.of());
        policies.deleteById(missingPolicy.workspaceId());
        assertThat(authorize(missingPolicy, action(READ, Set.of())).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.POLICY_DISABLED);

        Fixture missingClient = fixture(READ, READ, Set.of());
        clients.deleteById(missingClient.clientId());
        assertThat(authorize(missingClient, action(READ, Set.of())).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.CLIENT_DISABLED);

        Fixture toggled = fixture(READ, READ, Set.of());
        service.setClientEnabled(toggled.clientId(), false);
        assertThat(authorize(toggled, action(READ, Set.of())).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.CLIENT_DISABLED);
        service.setClientEnabled(toggled.clientId(), true);
        McpGrant old = grants.findById(toggled.grant().getGrantId()).orElseThrow();
        assertThat(old.getStatus()).isEqualTo(McpGrantStatus.SECURITY_REVOKED);
        assertThat(old.getRevocationReason()).isEqualTo(McpGrantRevocationReason.CLIENT_DISABLED);
    }

    @Test
    void internalEndpointRequiresTokenRejectsUnknownFieldsAndInvalidEnums() throws Exception {
        Fixture fixture = fixture(READ, READ, Set.of());
        String url = url(fixture.grant().getGrantId());
        String valid = mapper.writeValueAsString(request(fixture, action(READ, Set.of())));

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_INTERNAL_TOKEN"));

        String unknownOuter = valid.substring(0, valid.length() - 1) + ",\"unexpected\":true}";
        mockMvc.perform(post(url).header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(unknownOuter))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        String unknownAction = valid.replace("\"phase\":\"TOOL_CALL\"",
                "\"phase\":\"TOOL_CALL\",\"unexpected\":true");
        mockMvc.perform(post(url).header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(unknownAction))
                .andExpect(status().isBadRequest());

        String invalidEnum = valid.replace("\"TOOL_CALL\"", "\"tool_call\"");
        mockMvc.perform(post(url).header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(invalidEnum))
                .andExpect(status().isBadRequest());
    }

    @Test
    void internalTokenRejectsBeforeAnyBodyParsing() throws Exception {
        String endpoint = url(UUID.randomUUID());
        String oversized = "[\"" + "x".repeat(5 * 1024 * 1024) + "\"]";
        for (String body : List.of("{", "{}", "{\"action\":{\"phase\":0}}", oversized)) {
            mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_INTERNAL_TOKEN"));
            mockMvc.perform(post(endpoint).header("X-Internal-Token", "wrong")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post(endpoint).header("X-Internal-Token", "")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void internalEndpointRejectsCoercionDuplicatesNullsAndWrongContainers() throws Exception {
        Fixture fixture = fixture(READ, READ, Set.of(WorkspacePermission.WIKI_INGEST));
        String endpoint = url(fixture.grant().getGrantId());
        ObjectNode valid = (ObjectNode) mapper.valueToTree(request(
                fixture, action(READ, Set.of(WorkspacePermission.WIKI_INGEST))));
        List<ObjectNode> invalid = new ArrayList<>();

        invalid.add(changed(valid, "token_context", "subject", 123));
        invalid.add(changed(valid, "token_context", "workspace_id", true));
        invalid.add(changed(valid, "token_context", "client_id", 123));
        invalid.add(changed(valid, "token_context", "policy_generation", String.valueOf(fixture.generation())));
        invalid.add(changed(valid, "action", "phase", 0));
        invalid.add(changed(valid, "action", "execution_basis", 0));

        ObjectNode numericScope = valid.deepCopy();
        ((ArrayNode) numericScope.path("token_context").path("scopes")).set(0, mapper.getNodeFactory().numberNode(0));
        invalid.add(numericScope);
        ObjectNode numericPermission = valid.deepCopy();
        ((ArrayNode) numericPermission.path("action").path("required_permissions"))
                .set(0, mapper.getNodeFactory().numberNode(0));
        invalid.add(numericPermission);

        ObjectNode duplicateScope = valid.deepCopy();
        ((ArrayNode) duplicateScope.path("token_context").path("scopes")).add("read");
        invalid.add(duplicateScope);
        ObjectNode duplicatePermission = valid.deepCopy();
        ((ArrayNode) duplicatePermission.path("action").path("required_permissions")).add("wiki.ingest");
        invalid.add(duplicatePermission);

        ObjectNode unknownOuter = valid.deepCopy();
        unknownOuter.put("unknown", true);
        invalid.add(unknownOuter);
        ObjectNode unknownContext = valid.deepCopy();
        ((ObjectNode) unknownContext.path("token_context")).put("unknown", true);
        invalid.add(unknownContext);
        ObjectNode unknownAction = valid.deepCopy();
        ((ObjectNode) unknownAction.path("action")).put("unknown", true);
        invalid.add(unknownAction);

        ObjectNode missingGeneration = valid.deepCopy();
        ((ObjectNode) missingGeneration.path("token_context")).remove("policy_generation");
        invalid.add(missingGeneration);
        invalid.add(changed(valid, "token_context", "subject", null));
        invalid.add(changed(valid, "action", "phase", null));
        ObjectNode wrongScopes = valid.deepCopy();
        ((ObjectNode) wrongScopes.path("token_context")).set("scopes", mapper.createObjectNode());
        invalid.add(wrongScopes);
        ObjectNode wrongPermissions = valid.deepCopy();
        ((ObjectNode) wrongPermissions.path("action")).put("required_permissions", "wiki.ingest");
        invalid.add(wrongPermissions);

        for (ObjectNode body : invalid) {
            mockMvc.perform(post(endpoint).header("X-Internal-Token", internalToken)
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(post(endpoint).header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(valid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ALLOW"));
    }

    @Test
    void openApiClosesAndRequiresTheExactAuthorizationResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.TokenContext.required",
                        containsInAnyOrder("subject", "workspace_id", "client_id", "policy_generation", "scopes")))
                .andExpect(jsonPath("$.components.schemas.McpAuthorizationResponse.additionalProperties").value(false))
                .andExpect(jsonPath("$.components.schemas.GrantView.additionalProperties").value(false))
                .andExpect(jsonPath("$.components.schemas.PolicyView.additionalProperties").value(false))
                .andExpect(jsonPath("$.components.schemas.CurrentAccessView.additionalProperties").value(false))
                .andExpect(jsonPath("$.components.schemas.CurrentAccessView.properties.role.enum",
                        containsInAnyOrder("OWNER", "MEMBER", "NONE")))
                .andExpect(jsonPath("$.components.schemas.McpAuthorizationResponse.properties.deny_reason.oneOf[0].type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.McpAuthorizationResponse.properties.deny_reason.oneOf[0].enum",
                        containsInAnyOrder("TOKEN_CONTEXT_MISMATCH", "GRANT_INACTIVE", "POLICY_DISABLED",
                                "POLICY_GENERATION_MISMATCH", "NOT_MEMBER", "PERMISSION_REVOKED",
                                "INSUFFICIENT_SCOPE", "CLIENT_DISABLED", "PURGED", "AUTHZ_UNAVAILABLE")))
                .andExpect(jsonPath("$.components.schemas.McpAuthorizationResponse.properties.deny_reason.oneOf[1].type")
                        .value("null"))
                .andExpect(jsonPath("$.components.schemas.McpAuthorizationResponse.properties.grant.oneOf[0]['$ref']")
                        .value("#/components/schemas/GrantView"))
                .andExpect(jsonPath("$.components.schemas.McpAuthorizationResponse.properties.policy.oneOf[0]['$ref']")
                        .value("#/components/schemas/PolicyView"))
                .andExpect(jsonPath("$.components.schemas.McpAuthorizationResponse.properties.current_access.oneOf[0]['$ref']")
                        .value("#/components/schemas/CurrentAccessView"))
                .andExpect(jsonPath("$.components.schemas.McpAuthorizationResponse.properties.grant.oneOf[1].type")
                        .value("null"))
                .andExpect(jsonPath("$.components.schemas.McpAuthorizationResponse.properties.policy.oneOf[1].type")
                        .value("null"))
                .andExpect(jsonPath("$.components.schemas.McpAuthorizationResponse.properties.current_access.oneOf[1].type")
                        .value("null"))
                .andExpect(jsonPath("$.components.schemas.GrantView.properties.revocation_reason.oneOf[0].type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.GrantView.properties.revocation_reason.oneOf[0].enum",
                        containsInAnyOrder("USER_DISCONNECT", "ADMIN_GRANT_REVOKE", "WORKSPACE_POLICY_DISABLED",
                                "REFRESH_REUSE", "CLIENT_DISABLED", "USER_PURGED", "WORKSPACE_PURGED")))
                .andExpect(jsonPath("$.components.schemas.GrantView.properties.revocation_reason.oneOf[1].type")
                        .value("null"));
    }

    @Test
    void internalEndpointMapsAllowAndRejectsNonV4GrantIdsAndInvalidExecutionBasis() throws Exception {
        Fixture fixture = fixture(READ, READ, Set.of(WorkspacePermission.WIKI_INGEST));
        String valid = mapper.writeValueAsString(
                request(fixture, action(READ, Set.of(WorkspacePermission.WIKI_INGEST))));
        mockMvc.perform(post(url(fixture.grant().getGrantId())).header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.deny_reason").value(nullValue()))
                .andExpect(jsonPath("$.grant.grant_id").value(fixture.grant().getGrantId().toString()))
                .andExpect(jsonPath("$.grant.revocation_reason").value(nullValue()))
                .andExpect(jsonPath("$.policy.enabled").value(true))
                .andExpect(jsonPath("$.current_access.role").value("MEMBER"))
                .andExpect(jsonPath("$.effective_scopes[0]").value("read"));

        mockMvc.perform(post(url(UUID.randomUUID())).header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("DENY"))
                .andExpect(jsonPath("$.deny_reason").value("GRANT_INACTIVE"))
                .andExpect(jsonPath("$.grant").value(nullValue()))
                .andExpect(jsonPath("$.policy").value(nullValue()))
                .andExpect(jsonPath("$.current_access").value(nullValue()));

        mockMvc.perform(post(url(fixture.grant().getGrantId())).header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid.replace("wiki.ingest", "wiki.unknown")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(url(UUID.fromString("00000000-0000-1000-8000-000000000001")))
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isBadRequest());

        String invalidBasis = valid.replace("\"ACTIVE_GRANT\"", "\"EXPLICIT_WEB_APPROVAL\"");
        mockMvc.perform(post(url(fixture.grant().getGrantId())).header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(invalidBasis))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userAndWorkspaceDeletionRetainPurgedGrantFences() {
        Fixture userPurge = fixture(READ, READ, Set.of());
        jdbc.update("DELETE FROM users WHERE id = ?", userPurge.userId());
        McpGrant userGrant = grants.findById(userPurge.grant().getGrantId()).orElseThrow();
        assertThat(userGrant.getStatus()).isEqualTo(McpGrantStatus.PURGED);
        assertThat(userGrant.getRevocationReason()).isEqualTo(McpGrantRevocationReason.USER_PURGED);

        Fixture workspacePurge = fixture(READ, READ, Set.of());
        long generation = workspacePurge.generation();
        jdbc.update("DELETE FROM workspaces WHERE id = ?", workspacePurge.workspaceId());
        McpGrant workspaceGrant = grants.findById(workspacePurge.grant().getGrantId()).orElseThrow();
        assertThat(workspaceGrant.getStatus()).isEqualTo(McpGrantStatus.PURGED);
        assertThat(workspaceGrant.getRevocationReason()).isEqualTo(McpGrantRevocationReason.WORKSPACE_PURGED);
        assertThat(policies.findById(workspacePurge.workspaceId()).orElseThrow().isEnabled()).isFalse();
        assertThat(policies.findById(workspacePurge.workspaceId()).orElseThrow().getGeneration())
                .isEqualTo(generation + 1);
    }

    @Test
    void workspacePurgeWithoutPriorPolicyCannotBeRecreated() {
        Base base = base(Set.of());
        jdbc.update("DELETE FROM workspaces WHERE id = ?", base.workspaceId());

        assertThat(policies.findById(base.workspaceId())).isEmpty();
        assertThatThrownBy(() -> service.updateWorkspacePolicy(base.workspaceId(), true, READ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyScopeChangeRevokesOldGenerationAndPostCommitAuthorizationDenies() {
        Fixture fixture = fixture(Set.of(McpScope.READ, McpScope.WRITE_PROPOSE), READ, Set.of());
        service.updateWorkspacePolicy(fixture.workspaceId(), true, Set.of(McpScope.WRITE_PROPOSE));

        McpGrant old = grants.findById(fixture.grant().getGrantId()).orElseThrow();
        assertThat(old.getStatus()).isEqualTo(McpGrantStatus.ADMIN_REVOKED);
        assertThat(authorize(fixture, action(READ, Set.of())).decision())
                .isEqualTo(McpAuthorizationResponse.Decision.DENY);
    }

    @Test
    void databaseQueryFailureReturnsRedactedUnavailableAndNextRequestRecovers() {
        Fixture fixture = fixture(READ, READ, Set.of());
        jdbc.execute("ALTER TABLE mcp_grants RENAME TO mcp_grants_unavailable_i1_test");
        McpAuthorizationResponse unavailable;
        try {
            unavailable = authorize(fixture, action(READ, Set.of()));
        } finally {
            jdbc.execute("ALTER TABLE mcp_grants_unavailable_i1_test RENAME TO mcp_grants");
        }

        assertThat(unavailable.decision()).isEqualTo(McpAuthorizationResponse.Decision.DENY);
        assertThat(unavailable.denyReason()).isEqualTo(McpAuthorizationResponse.DenyReason.AUTHZ_UNAVAILABLE);
        assertThat(unavailable.grant()).isNull();
        assertThat(unavailable.policy()).isNull();
        assertThat(unavailable.currentAccess()).isNull();
        assertThat(authorize(fixture, action(READ, Set.of())).decision())
                .isEqualTo(McpAuthorizationResponse.Decision.ALLOW);
    }

    @Test
    void grantCreationSerializesWithClientWorkspaceScopeMemberAndUserTransitions() throws Exception {
        Base client = enabledBase();
        McpGrant clientGrant = raceCreate(client, () -> service.setClientEnabled(client.clientId(), false));
        assertThat(grants.findById(clientGrant.getGrantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.SECURITY_REVOKED);

        Base workspace = enabledBase();
        McpGrant workspaceGrant = raceCreate(workspace,
                () -> service.updateWorkspacePolicy(workspace.workspaceId(), false, READ));
        assertThat(grants.findById(workspaceGrant.getGrantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.ADMIN_REVOKED);

        Base scope = enabledBase();
        McpGrant scopeGrant = raceCreate(scope,
                () -> service.updateWorkspacePolicy(scope.workspaceId(), true, Set.of(McpScope.WRITE_PROPOSE)));
        assertThat(grants.findById(scopeGrant.getGrantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.ADMIN_REVOKED);

        Base member = enabledBase();
        McpGrant memberGrant = raceCreate(member,
                () -> memberService.remove(member.userId(), member.workspaceId(), member.userId()));
        assertThat(grants.findById(memberGrant.getGrantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.USER_DISCONNECTED);

        Base user = enabledBase();
        McpGrant userGrant = raceCreate(user, () -> jdbc.update("DELETE FROM users WHERE id = ?", user.userId()));
        assertThat(grants.findById(userGrant.getGrantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.PURGED);
    }

    @Test
    void grantCreationSerializesWithBothWorkspaceHardDeletePaths() throws Exception {
        Base scheduled = enabledBase();
        McpGrant scheduledGrant = raceCreate(scheduled, () -> {
            jdbc.update("UPDATE workspaces SET deleted_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minus(Duration.ofDays(31))), scheduled.workspaceId());
            expiredCleanup.run(Instant.now());
        });
        assertThat(grants.findById(scheduledGrant.getGrantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.PURGED);

        Base queued = enabledBase();
        McpGrant queuedGrant = raceCreate(queued, () -> {
            jdbc.update("INSERT INTO data_purge_requests(kind, target_id) VALUES ('workspace', ?)", queued.workspaceId());
            purgeRequests.run(Instant.now().plusSeconds(1));
        });
        assertThat(grants.findById(queuedGrant.getGrantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.PURGED);
    }

    @Test
    void policyCreationSerializesWithPurgeAndCannotReviveMissingPolicyFence() throws Exception {
        Base base = base(Set.of());
        try (DatabaseGate gate = pauseNextInsert("mcp_workspace_policies")) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> updating = executor.submit(() -> service.updateWorkspacePolicy(base.workspaceId(), true, READ));
            gate.awaitReached();
            Future<?> purging = executor.submit(() -> jdbc.update(
                    "DELETE FROM workspaces WHERE id = ?", base.workspaceId()));
            assertStillBlocked(purging);
            gate.release();
            updating.get(10, TimeUnit.SECONDS);
            purging.get(10, TimeUnit.SECONDS);
        } finally {
            gate.release();
            executor.shutdownNow();
        }
        }

        assertThat(workspaces.existsById(base.workspaceId())).isFalse();
        assertThat(policies.findById(base.workspaceId()).orElseThrow().isEnabled()).isFalse();
        assertThatThrownBy(() -> service.updateWorkspacePolicy(base.workspaceId(), true, READ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overlappingBulkDirectAndPurgeRevocationsFinishMonotonically() throws Exception {
        Base base = enabledBase();
        McpGrant first = service.createGrant(base.userId(), base.workspaceId(), base.clientId(), READ);
        McpGrant second = service.createGrant(base.userId(), base.workspaceId(), base.clientId(), READ);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = List.of(
                    executor.submit(starting(start,
                            () -> service.updateWorkspacePolicy(base.workspaceId(), false, READ))),
                    executor.submit(starting(start, () -> service.setClientEnabled(base.clientId(), false))),
                    executor.submit(starting(start,
                            () -> service.revokeGrant(first.getGrantId(), McpGrantRevocationReason.REFRESH_REUSE))),
                    executor.submit(starting(start,
                            () -> jdbc.update("DELETE FROM users WHERE id = ?", base.userId()))));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(grants.findAllById(List.of(first.getGrantId(), second.getGrantId())))
                .extracting(McpGrant::getStatus)
                .containsOnly(McpGrantStatus.PURGED);
    }

    private McpGrant raceCreate(Base base, Runnable opposing) throws Exception {
        try (DatabaseGate gate = pauseNextInsert("mcp_grants")) {
        CountDownLatch opposingStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<McpGrant> creating = executor.submit(
                    () -> service.createGrant(base.userId(), base.workspaceId(), base.clientId(), READ));
            gate.awaitReached();
            Future<?> mutation = executor.submit(() -> {
                opposingStarted.countDown();
                opposing.run();
            });
            assertThat(opposingStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertStillBlocked(mutation);
            gate.release();
            McpGrant grant = creating.get(10, TimeUnit.SECONDS);
            mutation.get(10, TimeUnit.SECONDS);
            return grant;
        } finally {
            gate.release();
            executor.shutdownNow();
        }
        }
    }

    private DatabaseGate pauseNextInsert(String table) throws Exception {
        return new DatabaseGate(table);
    }

    private static void assertStillBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    private static Callable<Void> starting(CountDownLatch start, Runnable action) {
        return () -> {
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            action.run();
            return null;
        };
    }

    private ObjectNode changed(ObjectNode source, String object, String field, Object value) {
        ObjectNode changed = source.deepCopy();
        ObjectNode target = (ObjectNode) changed.path(object);
        if (value == null) {
            target.putNull(field);
        } else if (value instanceof String text) {
            target.put(field, text);
        } else if (value instanceof Integer number) {
            target.put(field, number);
        } else if (value instanceof Boolean bool) {
            target.put(field, bool);
        } else {
            throw new IllegalArgumentException("unsupported test value");
        }
        return changed;
    }

    private Base enabledBase() {
        Base base = base(Set.of());
        service.updateWorkspacePolicy(base.workspaceId(), true, READ);
        return base;
    }

    private final class DatabaseGate implements AutoCloseable {
        private static final AtomicLong KEYS = new AtomicLong(92_001);
        private final long key = KEYS.getAndIncrement();
        private final String function = "pause_mcp_insert_" + key;
        private final String trigger = function + "_trigger";
        private final String table;
        private final Connection holder;
        private boolean released;

        DatabaseGate(String table) throws Exception {
            this.table = table;
            holder = dataSource.getConnection();
            holder.createStatement().execute("SELECT pg_advisory_lock(" + key + ")");
            jdbc.execute("""
                    CREATE FUNCTION %s() RETURNS trigger
                    LANGUAGE plpgsql AS $$
                    BEGIN
                        PERFORM pg_advisory_xact_lock(%d);
                        RETURN NEW;
                    END;
                    $$
                    """.formatted(function, key));
            jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON " + table
                    + " FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        }

        void awaitReached() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                Boolean waiting = jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_locks
                            WHERE locktype = 'advisory' AND NOT granted AND classid = 0 AND objid = ?
                        )
                        """, Boolean.class, key);
                if (Boolean.TRUE.equals(waiting)) {
                    return;
                }
                Thread.sleep(10);
            }
            throw new IllegalStateException("database test gate timed out");
        }

        void release() throws Exception {
            if (!released) {
                holder.createStatement().execute("SELECT pg_advisory_unlock(" + key + ")");
                released = true;
            }
        }

        @Override
        public void close() throws Exception {
            release();
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger + " ON " + table);
            jdbc.execute("DROP FUNCTION IF EXISTS " + function + "()");
            holder.close();
        }
    }

    private Fixture fixture(Set<McpScope> policyScopes, Set<McpScope> grantScopes,
                            Set<WorkspacePermission> permissions) {
        Base base = base(permissions);
        var policy = service.updateWorkspacePolicy(base.workspaceId(), true, policyScopes);
        McpGrant grant = service.createGrant(base.userId(), base.workspaceId(), base.clientId(), grantScopes);
        return new Fixture(base.userId(), base.workspaceId(), base.clientId(), policy.getGeneration(), grant);
    }

    private Base base(Set<WorkspacePermission> permissions) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String clientId = "https://client.example/" + suffix;
        tx.executeWithoutResult(status -> {
            User user = users.save(new User(userId, suffix + "@example.test", User.PROVIDER_LOCAL, "MCP Test", null));
            Workspace workspace = workspaces.save(new Workspace(workspaceId, "MCP Test"));
            WorkspaceMember member = new WorkspaceMember(workspace, user, WorkspaceRole.MEMBER);
            member.replacePermissions(permissions);
            members.save(member);
        });
        service.setClientEnabled(clientId, true);
        return new Base(userId, workspaceId, clientId);
    }

    private McpAuthorizationResponse authorize(Fixture fixture, McpAuthorizationRequest.Action action) {
        return service.authorize(fixture.grant().getGrantId(), request(fixture, action));
    }

    private McpAuthorizationRequest request(Fixture fixture, McpAuthorizationRequest.Action action) {
        return request(fixture, action, fixture.userId(), fixture.workspaceId(), fixture.clientId(),
                fixture.generation(), fixture.grant().getScopes());
    }

    private McpAuthorizationRequest request(Fixture fixture, McpAuthorizationRequest.Action action,
                                            String userId, String workspaceId, String clientId,
                                            long generation, Set<McpScope> scopes) {
        return new McpAuthorizationRequest(
                new McpAuthorizationRequest.TokenContext(userId, workspaceId, clientId, generation, scopes), action);
    }

    private static McpAuthorizationRequest.Action action(Set<McpScope> scopes,
                                                         Set<WorkspacePermission> permissions) {
        return new McpAuthorizationRequest.Action(McpAuthorizationRequest.Phase.TOOL_CALL, scopes, permissions,
                McpAuthorizationRequest.ExecutionBasis.ACTIVE_GRANT, null);
    }

    private static McpAuthorizationRequest.Action explicitAction(long approvedGeneration) {
        return new McpAuthorizationRequest.Action(McpAuthorizationRequest.Phase.OPERATION_BOUNDARY, READ, Set.of(),
                McpAuthorizationRequest.ExecutionBasis.EXPLICIT_WEB_APPROVAL, approvedGeneration);
    }

    private static String url(UUID grantId) {
        return "/internal/mcp/grants/" + grantId + "/authorize";
    }

    private record Base(String userId, String workspaceId, String clientId) {}
    private record Fixture(String userId, String workspaceId, String clientId, long generation, McpGrant grant) {}

    @Test
    void mixedScopeGrantAllowsBoundServiceActorPhasesButToolCallsRemainExact() {
        Set<McpScope> mixed = Set.of(McpScope.READ, McpScope.WRITE_PROPOSE);
        Fixture fixture = fixture(mixed, mixed, Set.of());
        when(clientMetadata.resolve(fixture.clientId())).thenReturn(new McpClientMetadata(
                fixture.clientId(), Optional.of("Test MCP Client"), List.of("https://client.example/callback"),
                Set.of("client.example"), "none", Set.of("authorization_code", "refresh_token")));
        var actor = new McpAuthorizationRequest.TokenContext(fixture.userId(), fixture.workspaceId(),
                fixture.clientId(), fixture.generation(), WRITE);
        var proposal = new McpAuthorizationRequest.Action(McpAuthorizationRequest.Phase.PROPOSAL_CREATE,
                WRITE, Set.of(), McpAuthorizationRequest.ExecutionBasis.ACTIVE_GRANT, null);
        var approval = new McpAuthorizationRequest.Action(McpAuthorizationRequest.Phase.WEB_APPROVAL,
                WRITE, Set.of(), McpAuthorizationRequest.ExecutionBasis.ACTIVE_GRANT, null);
        var boundary = new McpAuthorizationRequest.Action(McpAuthorizationRequest.Phase.OPERATION_BOUNDARY,
                Set.of(), Set.of(), McpAuthorizationRequest.ExecutionBasis.EXPLICIT_WEB_APPROVAL,
                fixture.generation());

        assertThat(service.authorize(fixture.grant().getGrantId(),
                new McpAuthorizationRequest(actor, proposal)).decision()).isEqualTo(McpAuthorizationResponse.Decision.ALLOW);
        assertThat(service.authorize(fixture.grant().getGrantId(),
                new McpAuthorizationRequest(actor, approval)).clientName()).isEqualTo("Test MCP Client");
        assertThat(service.authorize(fixture.grant().getGrantId(),
                new McpAuthorizationRequest(actor, boundary)).decision()).isEqualTo(McpAuthorizationResponse.Decision.ALLOW);
        assertThat(service.authorize(fixture.grant().getGrantId(), new McpAuthorizationRequest(actor,
                action(WRITE, Set.of()))).denyReason()).isEqualTo(McpAuthorizationResponse.DenyReason.TOKEN_CONTEXT_MISMATCH);

        var forged = new McpAuthorizationRequest.TokenContext(fixture.userId(), fixture.workspaceId(),
                fixture.clientId(), fixture.generation(), Set.of(McpScope.READ, McpScope.WRITE_PROPOSE, McpScope.WIKI_WRITE));
        assertThat(service.authorize(fixture.grant().getGrantId(),
                new McpAuthorizationRequest(forged, proposal)).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.TOKEN_CONTEXT_MISMATCH);

        service.revokeGrant(fixture.grant().getGrantId(), McpGrantRevocationReason.USER_DISCONNECT);
        assertThat(service.authorize(fixture.grant().getGrantId(),
                new McpAuthorizationRequest(actor, boundary)).decision()).isEqualTo(McpAuthorizationResponse.Decision.ALLOW);
        Fixture admin = fixture(mixed, mixed, Set.of());
        service.revokeGrant(admin.grant().getGrantId(), McpGrantRevocationReason.ADMIN_GRANT_REVOKE);
        var adminActor = new McpAuthorizationRequest.TokenContext(admin.userId(), admin.workspaceId(),
                admin.clientId(), admin.generation(), WRITE);
        var adminBoundary = new McpAuthorizationRequest.Action(McpAuthorizationRequest.Phase.OPERATION_BOUNDARY,
                Set.of(), Set.of(), McpAuthorizationRequest.ExecutionBasis.EXPLICIT_WEB_APPROVAL, admin.generation());
        assertThat(service.authorize(admin.grant().getGrantId(),
                new McpAuthorizationRequest(adminActor, adminBoundary)).decision())
                .isEqualTo(McpAuthorizationResponse.Decision.DENY);
    }

    @Test
    void wikiDraftProposalCreationAllowsMixedScopeActorAndKeepsEveryCurrentFence() {
        Set<McpScope> mixed = Set.of(McpScope.READ, McpScope.WIKI_WRITE);
        Set<McpScope> wiki = Set.of(McpScope.WIKI_WRITE);
        Set<WorkspacePermission> ingest = Set.of(WorkspacePermission.WIKI_INGEST);
        var draftCreation = new McpAuthorizationRequest.Action(McpAuthorizationRequest.Phase.PROPOSAL_CREATE,
                wiki, ingest, McpAuthorizationRequest.ExecutionBasis.ACTIVE_GRANT, null);

        Fixture allowed = fixture(mixed, mixed, ingest);
        var actor = new McpAuthorizationRequest.TokenContext(allowed.userId(), allowed.workspaceId(),
                allowed.clientId(), allowed.generation(), wiki);
        assertThat(service.authorize(allowed.grant().getGrantId(),
                new McpAuthorizationRequest(actor, draftCreation)).decision())
                .isEqualTo(McpAuthorizationResponse.Decision.ALLOW);
        assertThat(service.authorize(allowed.grant().getGrantId(), new McpAuthorizationRequest(actor,
                action(wiki, ingest))).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.TOKEN_CONTEXT_MISMATCH);

        Fixture noWiki = fixture(READ, READ, ingest);
        var noWikiActor = new McpAuthorizationRequest.TokenContext(noWiki.userId(), noWiki.workspaceId(),
                noWiki.clientId(), noWiki.generation(), wiki);
        assertThat(service.authorize(noWiki.grant().getGrantId(),
                new McpAuthorizationRequest(noWikiActor, draftCreation)).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.TOKEN_CONTEXT_MISMATCH);

        Fixture noPermission = fixture(mixed, mixed, Set.of());
        var noPermissionActor = new McpAuthorizationRequest.TokenContext(noPermission.userId(),
                noPermission.workspaceId(), noPermission.clientId(), noPermission.generation(), wiki);
        assertThat(service.authorize(noPermission.grant().getGrantId(),
                new McpAuthorizationRequest(noPermissionActor, draftCreation)).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.PERMISSION_REVOKED);

        Fixture disconnected = fixture(mixed, mixed, ingest);
        service.revokeGrant(disconnected.grant().getGrantId(), McpGrantRevocationReason.USER_DISCONNECT);
        var disconnectedActor = new McpAuthorizationRequest.TokenContext(disconnected.userId(),
                disconnected.workspaceId(), disconnected.clientId(), disconnected.generation(), wiki);
        assertThat(service.authorize(disconnected.grant().getGrantId(),
                new McpAuthorizationRequest(disconnectedActor, draftCreation)).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.GRANT_INACTIVE);

        Fixture revoked = fixture(mixed, mixed, ingest);
        service.revokeGrant(revoked.grant().getGrantId(), McpGrantRevocationReason.ADMIN_GRANT_REVOKE);
        var revokedActor = new McpAuthorizationRequest.TokenContext(revoked.userId(), revoked.workspaceId(),
                revoked.clientId(), revoked.generation(), wiki);
        assertThat(service.authorize(revoked.grant().getGrantId(),
                new McpAuthorizationRequest(revokedActor, draftCreation)).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.GRANT_INACTIVE);

        var wrongGeneration = new McpAuthorizationRequest.TokenContext(allowed.userId(), allowed.workspaceId(),
                allowed.clientId(), allowed.generation() + 1, wiki);
        assertThat(service.authorize(allowed.grant().getGrantId(),
                new McpAuthorizationRequest(wrongGeneration, draftCreation)).denyReason())
                .isEqualTo(McpAuthorizationResponse.DenyReason.TOKEN_CONTEXT_MISMATCH);
        service.updateWorkspacePolicy(allowed.workspaceId(), false, mixed);
        service.updateWorkspacePolicy(allowed.workspaceId(), true, mixed);
        assertThat(service.authorize(allowed.grant().getGrantId(),
                new McpAuthorizationRequest(actor, draftCreation)).decision())
                .isEqualTo(McpAuthorizationResponse.Decision.DENY);
    }
}
