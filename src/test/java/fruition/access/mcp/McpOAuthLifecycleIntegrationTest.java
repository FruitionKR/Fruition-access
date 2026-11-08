package fruition.access.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.access.mcp.domain.McpGrantRevocationReason;
import fruition.access.mcp.domain.McpGrantStatus;
import fruition.access.mcp.domain.McpScope;
import fruition.access.mcp.oauth.CimdDocumentFetcher;
import fruition.access.mcp.repository.McpGrantRepository;
import fruition.access.mcp.service.McpAuthorizationService;
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
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class McpOAuthLifecycleIntegrationTest {
    private static final String RESOURCE = "https://mcp.test.example/mcp";
    private static final String REGISTERED_REDIRECT = "http://127.0.0.1/callback";
    private static final String ACTUAL_REDIRECT = "http://127.0.0.1:43123/callback";
    private static final KeyPair RSA = rsa();

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry properties) {
        properties.add("app.mcp.oauth.enabled", () -> "true");
        properties.add("app.mcp.oauth.frontend-authorization-uri",
                () -> "http://127.0.0.1:3000/mcp/authorize");
        properties.add("app.jwt.secret", () -> "");
        properties.add("app.jwt.public-key", () -> pem("PUBLIC KEY", RSA.getPublic().getEncoded()));
        properties.add("app.jwt.private-key", () -> pem("PRIVATE KEY", RSA.getPrivate().getEncoded()));
        properties.add("app.jwt.key-id", () -> "mcp-test-key");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider jwt;
    @Autowired McpAuthorizationService authorization;
    @Autowired McpGrantRepository grants;
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean CimdDocumentFetcher fetcher;
    @MockitoBean DocumentInternalClient documentClient;
    @MockitoBean AiInternalClient aiClient;

    @BeforeEach
    void resetCredentialRows() {
        jdbc.update("DELETE FROM mcp_oauth_pending_authorizations");
        jdbc.update("DELETE FROM mcp_oauth_authorization_codes");
        jdbc.update("DELETE FROM mcp_oauth_refresh_families");
    }

    @Test
    void anonymousMetadataAndJwkExposeOnlyTheEnabledPublicClientContract() throws Exception {
        mvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("https://access.test.example"))
                .andExpect(jsonPath("$.authorization_endpoint").value("https://access.test.example/oauth2/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value("https://access.test.example/oauth2/token"))
                .andExpect(jsonPath("$.token_endpoint_auth_methods_supported[0]").value("none"))
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"))
                .andExpect(jsonPath("$.grant_types_supported.length()").value(2))
                .andExpect(jsonPath("$.grant_types_supported[0]").value("authorization_code"))
                .andExpect(jsonPath("$.grant_types_supported[1]").value("refresh_token"))
                .andExpect(jsonPath("$.client_id_metadata_document_supported").value(true))
                .andExpect(jsonPath("$.registration_endpoint").doesNotExist())
                .andExpect(jsonPath("$.revocation_endpoint").doesNotExist())
                .andExpect(jsonPath("$.introspection_endpoint").doesNotExist());
        mvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.keys[0].kid").value("mcp-test-key"));
        mvc.perform(get("/oauth2/authorize")).andExpect(status().isUnauthorized());
        mvc.perform(get("/oauth2/token")).andExpect(status().isNotFound());
    }

    @Test
    void anonymousHtmlAuthorizationHandsOnlyBoundedOAuthParametersToFixedFrontend() throws Exception {
        String clientId = "https://client.example/metadata?tenant=a%2Fb";
        String redirect = "http://127.0.0.1:43123/callback?return=%2Fhome&mode=a+b";
        String state = "opaque + /?&=%2F 한글";
        String authorizationUri = "/oauth2/authorize?response_type=" + formEncode("code")
                + "&client_id=" + formEncode(clientId) + "&redirect_uri=" + formEncode(redirect)
                + "&scope=" + formEncode("read write_propose") + "&state=" + formEncode(state)
                + "&code_challenge=" + formEncode("A".repeat(43))
                + "&code_challenge_method=" + formEncode("S256") + "&resource=" + formEncode(RESOURCE);
        MvcResult result = mvc.perform(get(URI.create(authorizationUri)).accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andReturn();

        URI location = URI.create(result.getResponse().getHeader(HttpHeaders.LOCATION));
        assertThat(location.getScheme()).isEqualTo("http");
        assertThat(location.getHost()).isEqualTo("127.0.0.1");
        assertThat(location.getPort()).isEqualTo(3000);
        assertThat(location.getPath()).isEqualTo("/mcp/authorize");
        assertThat(query(location, "response_type")).isEqualTo("code");
        assertThat(query(location, "client_id")).isEqualTo(clientId);
        assertThat(query(location, "redirect_uri")).isEqualTo(redirect);
        assertThat(query(location, "scope")).isEqualTo("read write_propose");
        assertThat(query(location, "state")).isEqualTo(state);
        assertThat(query(location, "code_challenge")).isEqualTo("A".repeat(43));
        assertThat(query(location, "code_challenge_method")).isEqualTo("S256");
        assertThat(query(location, "resource")).isEqualTo(RESOURCE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_oauth_pending_authorizations", Long.class)).isZero();
        verify(fetcher, never()).fetch(clientId);
    }

    @Test
    void browserHandoffRejectsHostileShapeAndApiOrBadBearerStillGets401() throws Exception {
        mvc.perform(get("/oauth2/authorize").accept(MediaType.TEXT_HTML).queryParam("next", "//evil.test"))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
        mvc.perform(get("/oauth2/authorize").accept(MediaType.TEXT_HTML)
                        .queryParam("state", "one", "two"))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        mvc.perform(get("/oauth2/authorize").accept(MediaType.TEXT_HTML)
                        .queryParam("state", "x".repeat(1025)))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        mvc.perform(get("/oauth2/authorize").accept(MediaType.TEXT_HTML)
                        .queryParam("redirect_uri", "!".repeat(2700)))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        mvc.perform(get("/oauth2/authorize").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        mvc.perform(get("/oauth2/authorize").accept(MediaType.ALL))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        mvc.perform(get("/oauth2/authorize").header(HttpHeaders.ACCEPT, "text/html;q=invalid"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        mvc.perform(get("/oauth2/authorize").accept(MediaType.TEXT_HTML)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer malformed-or-expired"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    void authenticatedPreviewStillRejectsUnknownAndDuplicateParametersWithoutCreatingPendingState()
            throws Exception {
        Fixture fixture = fixture();
        mvc.perform(get("/oauth2/authorize")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.webToken())
                        .queryParam("unexpected", "value"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/oauth2/authorize")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.webToken())
                        .queryParam("state", "one", "two"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_oauth_pending_authorizations", Long.class)).isZero();
    }

    @Test
    void realSecurityChainApprovesCodeIssuesJwtRotatesAndCommitsReuseRevocation() throws Exception {
        Fixture fixture = fixture();
        Approved approved = approve(fixture, verifier("lifecycle"));

        JsonNode first = exchangeCode(fixture, approved.code(), verifier("lifecycle"), ACTUAL_REDIRECT, RESOURCE, 200);
        String access = first.path("access_token").asText();
        assertThat(jwt.isValid(access)).isFalse();
        String originalRefresh = first.path("refresh_token").asText();
        var claims = Jwts.parser().verifyWith((RSAPublicKey) RSA.getPublic()).requireIssuer("fruition-access")
                .requireAudience(RESOURCE).build().parseSignedClaims(access).getPayload();
        assertThat(claims.getAudience()).containsExactly(RESOURCE);
        assertThat(claims.getSubject()).isEqualTo(fixture.userId());
        assertThat(claims.get("workspace_id", String.class)).isEqualTo(fixture.workspaceId());
        assertThat(claims.get("client_id", String.class)).isEqualTo(fixture.clientId());
        assertThat(claims.get("grant_id", String.class)).isEqualTo(approved.grantId().toString());
        assertThat(UUID.fromString(claims.get("grant_id", String.class)).version()).isEqualTo(4);
        assertThat(claims.get("policy_generation", Number.class).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(claims.get("scope", String.class)).isEqualTo("read");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getIssuedAt()).isBefore(claims.getExpiration());

        JsonNode rotated = refresh(fixture, originalRefresh, RESOURCE, null, 200);
        String newestRefresh = rotated.path("refresh_token").asText();
        assertThat(newestRefresh).isNotEqualTo(originalRefresh);
        refresh(fixture, originalRefresh, RESOURCE, null, 400);

        assertThat(grants.findById(approved.grantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.SECURITY_REVOKED);
        refresh(fixture, newestRefresh, RESOURCE, null, 400);
    }

    @Test
    void codeBindingsRejectTamperingWithoutConsumingTheCode() throws Exception {
        Fixture fixture = fixture();
        Fixture otherClient = fixture();
        String verifier = verifier("bindings");
        Approved approved = approve(fixture, verifier);

        exchangeCode(otherClient, approved.code(), verifier, ACTUAL_REDIRECT, RESOURCE, 400);
        exchangeCode(fixture, approved.code(), verifier("wrong"), ACTUAL_REDIRECT, RESOURCE, 400);
        exchangeCode(fixture, approved.code(), verifier, REGISTERED_REDIRECT, RESOURCE, 400);
        exchangeCode(fixture, approved.code(), verifier, ACTUAL_REDIRECT, RESOURCE + "/other", 400);
        JsonNode success = exchangeCode(fixture, approved.code(), verifier, ACTUAL_REDIRECT, RESOURCE, 200);
        refresh(fixture, success.path("refresh_token").asText(), RESOURCE, "wiki_write", 400);
        assertThat(grants.findById(approved.grantId()).orElseThrow().getStatus()).isEqualTo(McpGrantStatus.ACTIVE);
    }

    @Test
    void pkceAcceptsRfcUnreservedVerifierAndRejectsNonS256ChallengeLength() throws Exception {
        Fixture fixture = fixture();
        String verifier = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        Approved approved = approve(fixture, verifier);
        exchangeCode(fixture, approved.code(), verifier, ACTUAL_REDIRECT, RESOURCE, 200);

        Fixture invalid = fixture();
        mvc.perform(get("/oauth2/authorize")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + invalid.webToken())
                        .queryParam("response_type", "code").queryParam("client_id", invalid.clientId())
                        .queryParam("redirect_uri", ACTUAL_REDIRECT).queryParam("scope", "read")
                        .queryParam("code_challenge", "A".repeat(44))
                        .queryParam("code_challenge_method", "S256").queryParam("resource", RESOURCE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_oauth_pending_authorizations WHERE client_id=?",
                Long.class, invalid.clientId())).isZero();
    }

    @Test
    void expiredCodeAndTokenFormContextSubstitutionCannotMintDifferentContext() throws Exception {
        Fixture expired = fixture();
        String expiredVerifier = verifier("expired");
        Approved expiredCode = approve(expired, expiredVerifier);
        jdbc.update("UPDATE mcp_oauth_authorization_codes SET expires_at=now()-interval '1 second' WHERE grant_id=?",
                expiredCode.grantId());
        exchangeCode(expired, expiredCode.code(), expiredVerifier, ACTUAL_REDIRECT, RESOURCE, 400);
        assertThat(familyCount(expiredCode.grantId())).isZero();
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM mcp_oauth_authorization_codes WHERE grant_id=?",
                Boolean.class, expiredCode.grantId())).isTrue();

        Fixture original = fixture();
        String verifier = verifier("substitution");
        Approved approved = approve(original, verifier);
        MvcResult result = mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code").param("client_id", original.clientId())
                        .param("code", approved.code()).param("redirect_uri", ACTUAL_REDIRECT)
                        .param("code_verifier", verifier).param("resource", RESOURCE)
                        .param("user_id", "attacker").param("workspace_id", "other-workspace")
                        .param("scope", "wiki_write"))
                .andExpect(status().isOk()).andReturn();
        String access = json.readTree(result.getResponse().getContentAsByteArray()).path("access_token").asText();
        var claims = Jwts.parser().verifyWith((RSAPublicKey) RSA.getPublic()).build()
                .parseSignedClaims(access).getPayload();
        assertThat(claims.getSubject()).isEqualTo(original.userId());
        assertThat(claims.get("workspace_id", String.class)).isEqualTo(original.workspaceId());
        assertThat(claims.get("scope", String.class)).isEqualTo("read");
    }

    @Test
    void consentDecisionRequiresCsrfSameUserAndSameSession() throws Exception {
        Fixture fixture = fixture();
        Preview original = preview(fixture, verifier("decision-binding"));

        mvc.perform(post("/oauth2/authorize/{id}", original.body().path("pending_id").asText())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.webToken())
                        .cookie(original.session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"workspace_id\":\"" + fixture.workspaceId() + "\"}"))
                .andExpect(status().isForbidden());

        Fixture otherUser = fixture();
        mvc.perform(post("/oauth2/authorize/{id}", original.body().path("pending_id").asText())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherUser.webToken())
                        .header("X-CSRF-TOKEN", original.csrf()).cookie(original.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"workspace_id\":\"" + fixture.workspaceId() + "\"}"))
                .andExpect(status().isBadRequest());

        Preview otherSession = preview(fixture, verifier("other-session"));
        mvc.perform(post("/oauth2/authorize/{id}", original.body().path("pending_id").asText())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.webToken())
                        .header("X-CSRF-TOKEN", otherSession.csrf()).cookie(otherSession.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"workspace_id\":\"" + fixture.workspaceId() + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject(
                "SELECT decision IS NULL FROM mcp_oauth_pending_authorizations WHERE pending_id=?",
                Boolean.class, UUID.fromString(original.body().path("pending_id").asText()))).isTrue();
    }

    @Test
    void concurrentCodeExchangeHasExactlyOneSuccess() throws Exception {
        Fixture fixture = fixture();
        String verifier = verifier("code-race");
        Approved approved = approve(fixture, verifier);
        List<Integer> statuses = concurrent(() -> tokenStatus(fixture, approved.code(), verifier));
        assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_oauth_refresh_families WHERE grant_id=?",
                Long.class, approved.grantId())).isEqualTo(1);
    }

    @Test
    void concurrentRefreshLoserIsReuseAndRevokesTheWinningRotation() throws Exception {
        Fixture fixture = fixture();
        String verifier = verifier("refresh-race");
        Approved approved = approve(fixture, verifier);
        String refresh = exchangeCode(fixture, approved.code(), verifier, ACTUAL_REDIRECT, RESOURCE, 200)
                .path("refresh_token").asText();

        List<Integer> statuses = concurrent(() -> refreshStatus(fixture, refresh));
        assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        assertThat(grants.findById(approved.grantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.SECURITY_REVOKED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_oauth_refresh_tokens WHERE status='ACTIVE'",
                Long.class)).isZero();
    }

    @Test
    void refreshReuseOrderingPreservesBoundExceptionsAndActiveScopeNonConsumption() throws Exception {
        Fixture mismatchedScope = fixture();
        String scopeVerifier = verifier("used-scope");
        Approved scopeGrant = approve(mismatchedScope, scopeVerifier);
        String used = exchangeCode(mismatchedScope, scopeGrant.code(), scopeVerifier, ACTUAL_REDIRECT, RESOURCE, 200)
                .path("refresh_token").asText();
        refresh(mismatchedScope, used, RESOURCE, null, 200);
        refresh(mismatchedScope, used, RESOURCE, "wiki_write", 400);
        assertThat(familyStatus(scopeGrant.grantId())).isEqualTo("REVOKED");
        assertThat(activeRefreshCount(scopeGrant.grantId())).isZero();
        assertThat(grants.findById(scopeGrant.grantId()).orElseThrow().getStatus())
                .isEqualTo(McpGrantStatus.SECURITY_REVOKED);

        Fixture bound = fixture();
        String boundVerifier = verifier("bound-reuse");
        Approved boundGrant = approve(bound, boundVerifier);
        String boundUsed = exchangeCode(bound, boundGrant.code(), boundVerifier, ACTUAL_REDIRECT, RESOURCE, 200)
                .path("refresh_token").asText();
        refresh(bound, boundUsed, RESOURCE, null, 200);
        Fixture wrongClient = fixture();
        refresh(wrongClient, boundUsed, RESOURCE, null, 400);
        refresh(bound, boundUsed, RESOURCE + "/other", null, 400);
        assertThat(familyStatus(boundGrant.grantId())).isEqualTo("ACTIVE");
        assertThat(activeRefreshCount(boundGrant.grantId())).isEqualTo(1);
        assertThat(grants.findById(boundGrant.grantId()).orElseThrow().getStatus()).isEqualTo(McpGrantStatus.ACTIVE);

        Fixture active = fixture();
        String activeVerifier = verifier("active-scope");
        Approved activeGrant = approve(active, activeVerifier);
        String activeToken = exchangeCode(active, activeGrant.code(), activeVerifier, ACTUAL_REDIRECT, RESOURCE, 200)
                .path("refresh_token").asText();
        refresh(active, activeToken, RESOURCE, "wiki_write", 400);
        assertThat(familyStatus(activeGrant.grantId())).isEqualTo("ACTIVE");
        assertThat(activeRefreshCount(activeGrant.grantId())).isEqualTo(1);
        refresh(active, activeToken, RESOURCE, null, 200);
    }

    @Test
    void refreshRechecksCurrentMembershipWithoutConsumingToken() throws Exception {
        Fixture fixture = fixture();
        String verifier = verifier("refresh-current");
        Approved approved = approve(fixture, verifier);
        String refresh = exchangeCode(fixture, approved.code(), verifier, ACTUAL_REDIRECT, RESOURCE, 200)
                .path("refresh_token").asText();

        jdbc.update("DELETE FROM workspace_members WHERE workspace_id=? AND user_id=?",
                fixture.workspaceId(), fixture.userId());
        refresh(fixture, refresh, RESOURCE, null, 400);
        assertThat(activeRefreshCount(approved.grantId())).isEqualTo(1);
        assertThat(grants.findById(approved.grantId()).orElseThrow().getStatus()).isEqualTo(McpGrantStatus.ACTIVE);

        members.saveAndFlush(new WorkspaceMember(workspaces.findById(fixture.workspaceId()).orElseThrow(),
                users.findById(fixture.userId()).orElseThrow(), WorkspaceRole.OWNER));
        refresh(fixture, refresh, RESOURCE, null, 200);
    }

    @Test
    void pendingCodeAndRefreshDoNotDeadlockUserOrWorkspacePurge() throws Exception {
        for (PurgeTarget target : PurgeTarget.values()) {
            Fixture pending = fixture();
            Preview preview = preview(pending, verifier("pending-purge-" + target));
            int pendingStatus = racePurge(pending, target,
                    () -> decisionStatus(pending, preview, "APPROVE"));
            assertThat(pendingStatus).isEqualTo(400);
            assertThat(grantCount(pending.userId())).isZero();
            assertPurgeTerminal(pending, target);

            Fixture code = fixture();
            String codeVerifier = verifier("code-purge-" + target);
            Approved approved = approve(code, codeVerifier);
            int codeStatus = racePurge(code, target,
                    () -> tokenStatus(code, approved.code(), codeVerifier));
            assertThat(codeStatus).isEqualTo(400);
            assertThat(familyCount(approved.grantId())).isZero();
            assertPurgedGrant(approved.grantId(), target);

            Fixture refreshFixture = fixture();
            String refreshVerifier = verifier("refresh-purge-" + target);
            Approved refreshGrant = approve(refreshFixture, refreshVerifier);
            String refresh = exchangeCode(refreshFixture, refreshGrant.code(), refreshVerifier,
                    ACTUAL_REDIRECT, RESOURCE, 200).path("refresh_token").asText();
            int refreshStatus = racePurge(refreshFixture, target,
                    () -> refreshStatus(refreshFixture, refresh));
            assertThat(refreshStatus).isEqualTo(400);
            assertThat(familyCount(refreshGrant.grantId())).isZero();
            assertPurgedGrant(refreshGrant.grantId(), target);
        }
    }

    @Test
    void randomRefreshAndMembershipOrPurgeFencesCannotMintCredentials() throws Exception {
        Fixture random = fixture();
        Approved randomGrant = approve(random, verifier("random"));
        refresh(random, verifier("unknown-refresh"), RESOURCE, null, 400);
        assertThat(grants.findById(randomGrant.grantId()).orElseThrow().getStatus()).isEqualTo(McpGrantStatus.ACTIVE);

        Fixture member = fixture();
        String memberVerifier = verifier("member");
        Approved memberCode = approve(member, memberVerifier);
        jdbc.update("DELETE FROM workspace_members WHERE workspace_id=? AND user_id=?",
                member.workspaceId(), member.userId());
        exchangeCode(member, memberCode.code(), memberVerifier, ACTUAL_REDIRECT, RESOURCE, 400);

        Fixture policy = fixture();
        String policyVerifier = verifier("policy");
        Approved policyCode = approve(policy, policyVerifier);
        authorization.updateWorkspacePolicy(policy.workspaceId(), false, Set.of(McpScope.READ));
        exchangeCode(policy, policyCode.code(), policyVerifier, ACTUAL_REDIRECT, RESOURCE, 400);

        Fixture purged = fixture();
        String purgeVerifier = verifier("purge");
        Approved purgeCode = approve(purged, purgeVerifier);
        jdbc.update("DELETE FROM users WHERE id=?", purged.userId());
        exchangeCode(purged, purgeCode.code(), purgeVerifier, ACTUAL_REDIRECT, RESOURCE, 400);
        assertThat(grants.findById(purgeCode.grantId()).orElseThrow().getStatus()).isEqualTo(McpGrantStatus.PURGED);
    }

    @Test
    void previewAndDenialCreateNoGrant() throws Exception {
        Fixture fixture = fixture();
        Preview preview = preview(fixture, verifier("deny"));
        assertThat(grantCount(fixture.userId())).isZero();

        MvcResult denial = mvc.perform(post("/oauth2/authorize/{id}", preview.body().path("pending_id").asText())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.webToken())
                        .header("X-CSRF-TOKEN", preview.csrf()).cookie(preview.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DENY\"}"))
                .andExpect(status().isOk()).andReturn();
        URI redirect = URI.create(json.readTree(denial.getResponse().getContentAsByteArray())
                .path("redirect_uri").asText());
        assertThat(query(redirect, "error")).isEqualTo("access_denied");
        assertThat(grantCount(fixture.userId())).isZero();
    }

    @Test
    void disabledClientIsRejectedBeforeMetadataFetchOrConsent() throws Exception {
        Fixture fixture = fixture();
        String disabled = "https://disabled-client.example/metadata";
        mvc.perform(get("/oauth2/authorize")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.webToken())
                        .queryParam("response_type", "code").queryParam("client_id", disabled)
                        .queryParam("redirect_uri", ACTUAL_REDIRECT).queryParam("scope", "read")
                        .queryParam("code_challenge", verifier("disabled-client"))
                        .queryParam("code_challenge_method", "S256").queryParam("resource", RESOURCE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        verify(fetcher, never()).fetch(disabled);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_oauth_pending_authorizations WHERE client_id=?",
                Long.class, disabled)).isZero();
    }

    private Fixture fixture() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String userId = "oauth-user-" + suffix;
        String workspaceId = "oauth-workspace-" + suffix;
        String clientId = "https://client.example/metadata/" + suffix;
        User user = users.save(new User(userId, suffix + "@example.invalid", User.PROVIDER_LOCAL, "OAuth User", "hash"));
        Workspace workspace = workspaces.save(new Workspace(workspaceId, "OAuth Workspace"));
        members.save(new WorkspaceMember(workspace, user, WorkspaceRole.OWNER));
        authorization.setClientEnabled(clientId, true);
        authorization.updateWorkspacePolicy(workspaceId, true, Set.of(McpScope.READ));
        when(fetcher.fetch(clientId)).thenReturn(("""
                {"client_id":"%s","client_name":"Unverified Test Client",
                 "redirect_uris":["%s"],"token_endpoint_auth_method":"none",
                 "grant_types":["authorization_code","refresh_token"],"response_types":["code"]}
                """).formatted(clientId, REGISTERED_REDIRECT).getBytes(StandardCharsets.UTF_8));
        return new Fixture(userId, workspaceId, clientId,
                jwt.generateAccessToken(userId, suffix + "@example.invalid"));
    }

    private Approved approve(Fixture fixture, String verifier) throws Exception {
        Preview preview = preview(fixture, verifier);
        MvcResult decision = mvc.perform(post("/oauth2/authorize/{id}", preview.body().path("pending_id").asText())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.webToken())
                        .header("X-CSRF-TOKEN", preview.csrf()).cookie(preview.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"workspace_id\":\"" + fixture.workspaceId() + "\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(decision.getResponse().getContentAsByteArray());
        URI redirect = URI.create(body.path("redirect_uri").asText());
        assertThat(redirect.getHost()).isEqualTo("127.0.0.1");
        assertThat(redirect.getPort()).isEqualTo(43123);
        return new Approved(query(redirect, "code"), UUID.fromString(body.path("grant_id").asText()));
    }

    private Preview preview(Fixture fixture, String verifier) throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        MvcResult preview = mvc.perform(get("/oauth2/authorize")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.webToken())
                        .queryParam("response_type", "code").queryParam("client_id", fixture.clientId())
                        .queryParam("redirect_uri", ACTUAL_REDIRECT).queryParam("scope", "read")
                        .queryParam("state", "opaque-state").queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256").queryParam("resource", RESOURCE))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(jsonPath("$.client_id").value(fixture.clientId()))
                .andExpect(jsonPath("$.client_name").value("Unverified Test Client"))
                .andExpect(jsonPath("$.redirect_host").value("127.0.0.1"))
                .andExpect(jsonPath("$.app_identity_verified").value(false))
                .andExpect(jsonPath("$.workspaces[0].workspace_id").value(fixture.workspaceId()))
                .andReturn();
        JsonNode previewBody = json.readTree(preview.getResponse().getContentAsByteArray());
        String csrf = preview.getResponse().getHeader("X-CSRF-TOKEN");
        assertThat(csrf).isNotBlank();
        Cookie session = preview.getResponse().getCookie("SESSION");
        assertThat(session).isNotNull();
        return new Preview(previewBody, csrf, session);
    }

    private long grantCount(String userId) {
        return jdbc.queryForObject("SELECT count(*) FROM mcp_grants WHERE user_id=?", Long.class, userId);
    }

    private long familyCount(UUID grantId) {
        return jdbc.queryForObject("SELECT count(*) FROM mcp_oauth_refresh_families WHERE grant_id=?",
                Long.class, grantId);
    }

    private String familyStatus(UUID grantId) {
        return jdbc.queryForObject("SELECT status FROM mcp_oauth_refresh_families WHERE grant_id=?",
                String.class, grantId);
    }

    private long activeRefreshCount(UUID grantId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM mcp_oauth_refresh_tokens t
                JOIN mcp_oauth_refresh_families f ON f.family_id=t.family_id
                WHERE f.grant_id=? AND t.status='ACTIVE'
                """, Long.class, grantId);
    }

    private int decisionStatus(Fixture fixture, Preview preview, String decision) throws Exception {
        return mvc.perform(post("/oauth2/authorize/{id}", preview.body().path("pending_id").asText())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.webToken())
                        .header("X-CSRF-TOKEN", preview.csrf()).cookie(preview.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"" + decision + "\",\"workspace_id\":\""
                                + fixture.workspaceId() + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int racePurge(Fixture fixture, PurgeTarget target, ThrowingSupplier<Integer> operationCall)
            throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch identityLocked = new CountDownLatch(1);
        CountDownLatch deleteNow = new CountDownLatch(1);
        AtomicInteger purgePid = new AtomicInteger();
        try {
            Future<?> purge = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        purgePid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        jdbc.queryForObject(target.lockSql(), String.class, target.id(fixture));
                        identityLocked.countDown();
                        await(deleteNow);
                        jdbc.update(target.deleteSql(), target.id(fixture));
                    }));
            assertThat(identityLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> operation = executor.submit(operationCall::get);
            awaitDatabaseLock(operation, purgePid.get());
            deleteNow.countDown();
            purge.get(10, TimeUnit.SECONDS);
            return operation.get(10, TimeUnit.SECONDS);
        } finally {
            deleteNow.countDown();
            executor.shutdownNow();
        }
    }

    private void awaitDatabaseLock(Future<Integer> operation, int purgePid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname=current_database() AND pid<>? AND wait_event_type='Lock'
                    """, Long.class, purgePid) > 0) return;
            if (operation.isDone()) throw new AssertionError("credential operation completed before identity lock");
            Thread.sleep(10);
        }
        throw new AssertionError("credential operation did not reach the identity lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting for purge race");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private void assertPurgeTerminal(Fixture fixture, PurgeTarget target) {
        if (target == PurgeTarget.USER) {
            assertThat(users.findById(fixture.userId())).isEmpty();
        } else {
            assertThat(workspaces.findById(fixture.workspaceId())).isEmpty();
            assertThat(jdbc.queryForObject("SELECT enabled FROM mcp_workspace_policies WHERE workspace_id=?",
                    Boolean.class, fixture.workspaceId())).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_oauth_authorization_codes WHERE user_id=?",
                Long.class, fixture.userId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_oauth_refresh_families WHERE user_id=?",
                Long.class, fixture.userId())).isZero();
    }

    private void assertPurgedGrant(UUID grantId, PurgeTarget target) {
        var grant = grants.findById(grantId).orElseThrow();
        assertThat(grant.getStatus()).isEqualTo(McpGrantStatus.PURGED);
        assertThat(grant.getRevocationReason()).isEqualTo(target == PurgeTarget.USER
                ? McpGrantRevocationReason.USER_PURGED : McpGrantRevocationReason.WORKSPACE_PURGED);
    }

    private JsonNode exchangeCode(Fixture fixture, String code, String verifier, String redirect,
                                  String resource, int expected) throws Exception {
        MvcResult result = mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code").param("client_id", fixture.clientId())
                        .param("code", code).param("redirect_uri", redirect).param("code_verifier", verifier)
                        .param("resource", resource))
                .andExpect(status().is(expected)).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());
        if (expected == 400) assertThat(body.path("error").asText()).isEqualTo("invalid_grant");
        return body;
    }

    private JsonNode refresh(Fixture fixture, String refresh, String resource, String scope, int expected)
            throws Exception {
        var request = post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token").param("client_id", fixture.clientId())
                .param("refresh_token", refresh).param("resource", resource);
        if (scope != null) request.param("scope", scope);
        MvcResult result = mvc.perform(request).andExpect(status().is(expected)).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private int tokenStatus(Fixture fixture, String code, String verifier) throws Exception {
        return mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code").param("client_id", fixture.clientId())
                        .param("code", code).param("redirect_uri", ACTUAL_REDIRECT)
                        .param("code_verifier", verifier).param("resource", RESOURCE))
                .andReturn().getResponse().getStatus();
    }

    private int refreshStatus(Fixture fixture, String refresh) throws Exception {
        return mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token").param("client_id", fixture.clientId())
                        .param("refresh_token", refresh).param("resource", RESOURCE))
                .andReturn().getResponse().getStatus();
    }

    private List<Integer> concurrent(ThrowingSupplier<Integer> call) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> { start.await(); return call.get(); });
            Future<Integer> second = executor.submit(() -> { start.await(); return call.get(); });
            start.countDown();
            return List.of(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static String verifier(String seed) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("0123456789abcdef0123456789abcdef" + seed).getBytes(StandardCharsets.US_ASCII));
    }

    private static String query(URI uri, String name) {
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            if (pair[0].equals(name)) return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        throw new AssertionError("missing query parameter " + name);
    }

    private static String formEncode(String value) {
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
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(value)
                + "\n-----END " + type + "-----";
    }

    private record Fixture(String userId, String workspaceId, String clientId, String webToken) {}
    private record Approved(String code, UUID grantId) {}
    private record Preview(JsonNode body, String csrf, Cookie session) {}
    private enum PurgeTarget {
        USER("SELECT id FROM users WHERE id=? FOR UPDATE", "DELETE FROM users WHERE id=?"),
        WORKSPACE("SELECT id FROM workspaces WHERE id=? FOR UPDATE", "DELETE FROM workspaces WHERE id=?");

        private final String lockSql;
        private final String deleteSql;

        PurgeTarget(String lockSql, String deleteSql) {
            this.lockSql = lockSql;
            this.deleteSql = deleteSql;
        }

        String lockSql() { return lockSql; }
        String deleteSql() { return deleteSql; }
        String id(Fixture fixture) { return this == USER ? fixture.userId() : fixture.workspaceId(); }
    }
    @FunctionalInterface private interface ThrowingSupplier<T> { T get() throws Exception; }
}

@SpringBootTest(properties = "app.mcp.oauth.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class McpOAuthDisabledIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean DocumentInternalClient documentClient;
    @MockitoBean AiInternalClient aiClient;

    @Test
    void discoveryIsNotExposedWhileMcpOAuthIsDisabled() throws Exception {
        mvc.perform(get("/.well-known/oauth-authorization-server")).andExpect(status().isUnauthorized());
        mvc.perform(get("/oauth2/jwks")).andExpect(status().isNotFound());
    }
}
