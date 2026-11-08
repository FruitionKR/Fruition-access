package fruition.access.mcp.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.access.mcp.domain.McpGrant;
import fruition.access.mcp.domain.McpGrantRevocationReason;
import fruition.access.mcp.domain.McpScope;
import fruition.access.mcp.service.McpAuthorizationService;
import fruition.shared.security.JwtTokenProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "app.mcp.oauth.enabled", havingValue = "true")
public class McpOAuthCredentialService {
    private static final Pattern PKCE_CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern PKCE_VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final McpOAuthSettings settings;
    private final McpClientMetadataResolver clients;
    private final McpRedirectValidator redirects;
    private final McpAuthorizationService authorization;
    private final JwtTokenProvider jwt;

    public McpOAuthCredentialService(JdbcTemplate jdbc, McpOAuthSettings settings,
                                     McpClientMetadataResolver clients, McpRedirectValidator redirects,
                                     McpAuthorizationService authorization, JwtTokenProvider jwt) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clients = clients;
        this.redirects = redirects;
        this.authorization = authorization;
        this.jwt = jwt;
    }

    @Transactional(timeout = 4)
    public PendingAuthorization begin(String userId, String sessionId, AuthorizationRequest request) {
        if (!"code".equals(request.responseType()) || !"S256".equals(request.codeChallengeMethod())
                || request.resource() == null || !settings.resource().toString().equals(request.resource())
                || !validChallenge(request.codeChallenge()) || request.state() != null && request.state().length() > 1024) {
            throw new InvalidOAuthRequest("invalid_request");
        }
        Set<McpScope> scopes = scopes(request.scope());
        McpClientMetadata client = clients.resolve(request.clientId());
        ValidatedMcpRedirect redirect = redirects.validate(client, request.redirectUri());
        List<WorkspaceChoice> choices = workspaceChoices(userId, scopes);
        UUID pendingId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO mcp_oauth_pending_authorizations
                  (pending_id,user_id,session_id,client_id,client_name,redirect_uri,redirect_host,resource,scopes,
                   code_challenge,state,expires_at,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, pendingId, userId, sessionId, client.clientId(), client.displayName().orElse(null),
                redirect.uri(), redirect.host(), settings.resource().toString(), scopeText(scopes),
                request.codeChallenge(), request.state(), ts(now.plus(settings.pendingAuthorizationTtl())), ts(now));
        return new PendingAuthorization(pendingId, client.clientId(), client.displayName(), redirect.host(),
                false, scopes, choices, now.plus(settings.pendingAuthorizationTtl()));
    }

    @Transactional(timeout = 4)
    public AuthorizationDecision decide(String userId, String sessionId, UUID pendingId, DecisionRequest request) {
        PendingRow snapshot = pending(pendingId)
                .orElseThrow(() -> new InvalidOAuthRequest("invalid_request"));
        requirePending(snapshot, userId, sessionId, Instant.now());
        McpGrant grant = null;
        if (request.decision() == Decision.DENY) {
            try {
                authorization.lockPendingDecision(userId, snapshot.clientId());
            } catch (RuntimeException unavailable) {
                throw new InvalidOAuthRequest("invalid_request");
            }
        } else {
            if (!StringUtils.hasText(request.workspaceId())) throw new InvalidOAuthRequest("invalid_request");
            try {
                grant = authorization.createGrant(userId, request.workspaceId(), snapshot.clientId(),
                        parseScopes(snapshot.scopes()));
            } catch (RuntimeException denied) {
                throw new InvalidOAuthRequest("access_denied");
            }
        }
        PendingRow pending = pendingForUpdate(pendingId)
                .orElseThrow(() -> new InvalidOAuthRequest("invalid_request"));
        Instant now = Instant.now();
        if (!pending.equals(snapshot)) throw new InvalidOAuthRequest("invalid_request");
        requirePending(pending, userId, sessionId, now);
        if (request.decision() == Decision.DENY) {
            jdbc.update("UPDATE mcp_oauth_pending_authorizations SET decision='DENIED', decided_at=? WHERE pending_id=?",
                    ts(now), pendingId);
            return new AuthorizationDecision(redirectResponse(pending.redirectUri(), "error", "access_denied",
                    pending.state()), null);
        }
        String code = opaqueToken();
        jdbc.update("""
                INSERT INTO mcp_oauth_authorization_codes
                  (code_hash,grant_id,user_id,workspace_id,client_id,redirect_uri,resource,scopes,
                   code_challenge,policy_generation,expires_at,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, hash(code), grant.getGrantId(), grant.getUserId(), grant.getWorkspaceId(), grant.getClientId(),
                pending.redirectUri(), pending.resource(), pending.scopes(), pending.codeChallenge(),
                grant.getPolicyGeneration(), ts(now.plus(settings.authorizationCodeTtl())), ts(now));
        jdbc.update("UPDATE mcp_oauth_pending_authorizations SET decision='APPROVED', decided_at=? WHERE pending_id=?",
                ts(now), pendingId);
        return new AuthorizationDecision(redirectResponse(pending.redirectUri(), "code", code, pending.state()),
                grant.getGrantId());
    }

    @Transactional(timeout = 4)
    public Optional<IssuedTokens> exchangeCode(String code, String clientId, String redirectUri,
                                                String resource, String verifier) {
        byte[] codeHash = hash(code);
        Optional<CodeRow> found = code(codeHash);
        if (found.isEmpty()) return Optional.empty();
        CodeRow snapshot = found.get();
        if (!validCode(snapshot, clientId, redirectUri, resource, verifier, Instant.now())) return Optional.empty();
        McpAuthorizationService.LockedCredentialContext context;
        try {
            context = authorization.lockCredentialContext(snapshot.grantId(), snapshot.userId(),
                    snapshot.workspaceId(), snapshot.clientId());
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
        Optional<CodeRow> locked = codeForUpdate(codeHash);
        if (locked.isEmpty() || !locked.get().equals(snapshot)) return Optional.empty();
        CodeRow row = locked.get();
        Instant now = Instant.now();
        if (!validCode(row, clientId, redirectUri, resource, verifier, now)) return Optional.empty();
        Set<McpScope> scopes = parseScopes(row.scopes());
        McpGrant grant;
        try {
            grant = authorization.requireCredentialIssuance(context, scopes, row.policyGeneration());
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
        if (jdbc.update("UPDATE mcp_oauth_authorization_codes SET consumed_at=? WHERE code_hash=? AND consumed_at IS NULL",
                ts(now), codeHash) != 1) return Optional.empty();
        RefreshValue refresh = createFamily(grant, resource, scopes, now);
        return Optional.of(tokens(grant, scopes, resource, refresh.value(), now));
    }

    @Transactional(timeout = 4)
    public RefreshResult refresh(String refreshToken, String clientId, String resource, Set<String> requestedScopes) {
        byte[] tokenHash = hash(refreshToken);
        Optional<RefreshRow> found = refresh(tokenHash);
        if (found.isEmpty()) return RefreshResult.invalid();
        RefreshRow snapshot = found.get();
        if (!snapshot.clientId().equals(clientId) || !snapshot.resource().equals(resource)) return RefreshResult.invalid();
        McpAuthorizationService.LockedCredentialContext context;
        try {
            context = authorization.lockCredentialContext(snapshot.grantId(), snapshot.userId(),
                    snapshot.workspaceId(), snapshot.clientId());
        } catch (RuntimeException unavailable) {
            return RefreshResult.invalid();
        }
        Optional<RefreshRow> locked = refreshForUpdate(tokenHash);
        if (locked.isEmpty() || !sameRefreshIdentity(snapshot, locked.get())) return RefreshResult.invalid();
        RefreshRow row = locked.get();
        if (!row.clientId().equals(clientId) || !row.resource().equals(resource)) return RefreshResult.invalid();
        Instant now = Instant.now();
        if ("USED".equals(row.tokenStatus())) {
            revokeFamilyForReuse(row, context, now);
            return RefreshResult.reused();
        }
        Set<McpScope> scopes = parseScopes(row.scopes());
        if (!requestedScopes.isEmpty() && !requestedScopes.equals(scopeValues(scopes))) return RefreshResult.invalid();
        if (!"ACTIVE".equals(row.familyStatus()) || !"ACTIVE".equals(row.tokenStatus())
                || !row.tokenId().equals(row.currentTokenId()) || !row.expiresAt().isAfter(now)) {
            return RefreshResult.invalid();
        }
        McpGrant grant;
        try {
            grant = authorization.requireCredentialIssuance(context, scopes, row.policyGeneration());
        } catch (RuntimeException unavailable) {
            return RefreshResult.invalid();
        }
        jdbc.update("UPDATE mcp_oauth_refresh_tokens SET status='USED', used_at=? WHERE token_id=?", ts(now), row.tokenId());
        RefreshValue next = createRefresh(row.familyId(), row.sequence() + 1, now);
        jdbc.update("UPDATE mcp_oauth_refresh_families SET current_token_id=? WHERE family_id=?",
                next.tokenId(), row.familyId());
        return RefreshResult.success(tokens(grant, scopes, resource, next.value(), now));
    }

    private void revokeFamilyForReuse(RefreshRow row, McpAuthorizationService.LockedCredentialContext context,
                                      Instant now) {
        jdbc.update("UPDATE mcp_oauth_refresh_families SET status='REVOKED', revoked_at=? WHERE family_id=?",
                ts(now), row.familyId());
        jdbc.update("UPDATE mcp_oauth_refresh_tokens SET status='REVOKED' WHERE family_id=? AND status='ACTIVE'",
                row.familyId());
        authorization.revokeCredentialGrant(context, McpGrantRevocationReason.REFRESH_REUSE);
    }

    private IssuedTokens tokens(McpGrant grant, Set<McpScope> scopes, String resource,
                                String refreshToken, Instant now) {
        Set<String> scopeValues = scopeValues(scopes);
        String access = jwt.generateMcpAccessToken(grant.getUserId(), grant.getWorkspaceId(), grant.getClientId(),
                grant.getGrantId(), grant.getPolicyGeneration(), scopeValues, resource, settings.accessTokenTtl());
        return new IssuedTokens(access, now, now.plus(settings.accessTokenTtl()), refreshToken,
                now.plus(settings.refreshTokenTtl()), scopeValues);
    }

    private RefreshValue createFamily(McpGrant grant, String resource, Set<McpScope> scopes, Instant now) {
        UUID familyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mcp_oauth_refresh_families
                  (family_id,grant_id,user_id,workspace_id,client_id,resource,scopes,policy_generation,status,created_at)
                VALUES (?,?,?,?,?,?,?,?, 'ACTIVE', ?)
                """, familyId, grant.getGrantId(), grant.getUserId(), grant.getWorkspaceId(), grant.getClientId(),
                resource, scopeText(scopes), grant.getPolicyGeneration(), ts(now));
        RefreshValue token = createRefresh(familyId, 1, now);
        jdbc.update("UPDATE mcp_oauth_refresh_families SET current_token_id=? WHERE family_id=?",
                token.tokenId(), familyId);
        return token;
    }

    private RefreshValue createRefresh(UUID familyId, long sequence, Instant now) {
        String value = opaqueToken();
        UUID tokenId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mcp_oauth_refresh_tokens
                  (token_id,family_id,token_hash,sequence,status,issued_at,expires_at)
                VALUES (?,?,?,?, 'ACTIVE', ?,?)
                """, tokenId, familyId, hash(value), sequence, ts(now), ts(now.plus(settings.refreshTokenTtl())));
        return new RefreshValue(tokenId, value);
    }

    private List<WorkspaceChoice> workspaceChoices(String userId, Set<McpScope> requested) {
        List<WorkspaceChoice> result = new ArrayList<>();
        jdbc.query("""
                SELECT w.id,w.name,wm.role,p.generation
                FROM workspace_members wm
                JOIN workspaces w ON w.id=wm.workspace_id AND w.deleted_at IS NULL
                JOIN mcp_workspace_policies p ON p.workspace_id=w.id AND p.enabled=true
                WHERE wm.user_id=?
                ORDER BY w.created_at DESC
                """, rs -> {
            String workspaceId = rs.getString(1);
            Set<McpScope> allowed = parseScopes(String.join(" ", jdbc.queryForList(
                    "SELECT lower(scope) FROM mcp_workspace_policy_scopes WHERE workspace_id=? ORDER BY scope",
                    String.class, workspaceId)));
            if (allowed.containsAll(requested)) {
                result.add(new WorkspaceChoice(workspaceId, rs.getString(2), rs.getString(3), rs.getLong(4)));
            }
        }, userId);
        return List.copyOf(result);
    }

    private Optional<PendingRow> pending(UUID id) {
        return pending(id, "");
    }

    private Optional<PendingRow> pendingForUpdate(UUID id) {
        return pending(id, " FOR UPDATE");
    }

    private Optional<PendingRow> pending(UUID id, String lock) {
        return jdbc.query("""
                SELECT user_id,session_id,client_id,redirect_uri,resource,scopes,code_challenge,state,expires_at,decision
                FROM mcp_oauth_pending_authorizations WHERE pending_id=?""" + lock,
                (rs, n) -> new PendingRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getTimestamp(9).toInstant(), rs.getString(10)), id)
                .stream().findFirst();
    }

    private Optional<CodeRow> code(byte[] hash) {
        return code(hash, "");
    }

    private Optional<CodeRow> codeForUpdate(byte[] hash) {
        return code(hash, " FOR UPDATE");
    }

    private Optional<CodeRow> code(byte[] hash, String lock) {
        return jdbc.query("""
                SELECT grant_id,user_id,workspace_id,client_id,redirect_uri,resource,scopes,code_challenge,
                       policy_generation,expires_at,consumed_at
                FROM mcp_oauth_authorization_codes WHERE code_hash=?""" + lock,
                (rs, n) -> new CodeRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getLong(9),
                rs.getTimestamp(10).toInstant(), rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant()),
                hash).stream().findFirst();
    }

    private Optional<RefreshRow> refresh(byte[] hash) {
        return refresh(hash, "");
    }

    private Optional<RefreshRow> refreshForUpdate(byte[] hash) {
        return refresh(hash, " FOR UPDATE OF f,t");
    }

    private Optional<RefreshRow> refresh(byte[] hash, String lock) {
        return jdbc.query("""
                SELECT t.token_id,t.family_id,t.sequence,t.status,t.expires_at,
                       f.grant_id,f.user_id,f.workspace_id,f.client_id,f.resource,f.scopes,
                       f.policy_generation,f.status,f.current_token_id
                FROM mcp_oauth_refresh_tokens t
                JOIN mcp_oauth_refresh_families f ON f.family_id=t.family_id
                WHERE t.token_hash=?""" + lock,
                (rs, n) -> new RefreshRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getLong(3), rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getObject(6, UUID.class),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getLong(12),
                rs.getString(13), rs.getObject(14, UUID.class)), hash).stream().findFirst();
    }

    private static void requirePending(PendingRow pending, String userId, String sessionId, Instant now) {
        if (!pending.userId().equals(userId) || !pending.sessionId().equals(sessionId)
                || pending.decision() != null || !pending.expiresAt().isAfter(now)) {
            throw new InvalidOAuthRequest("invalid_request");
        }
    }

    private static boolean validCode(CodeRow row, String clientId, String redirectUri, String resource,
                                     String verifier, Instant now) {
        return row.consumedAt() == null && row.expiresAt().isAfter(now) && row.clientId().equals(clientId)
                && row.redirectUri().equals(redirectUri) && row.resource().equals(resource)
                && verifyPkce(verifier, row.codeChallenge());
    }

    private static boolean sameRefreshIdentity(RefreshRow first, RefreshRow second) {
        return first.tokenId().equals(second.tokenId()) && first.familyId().equals(second.familyId())
                && first.sequence() == second.sequence() && first.expiresAt().equals(second.expiresAt())
                && first.grantId().equals(second.grantId()) && first.userId().equals(second.userId())
                && first.workspaceId().equals(second.workspaceId()) && first.clientId().equals(second.clientId())
                && first.resource().equals(second.resource()) && first.scopes().equals(second.scopes())
                && first.policyGeneration() == second.policyGeneration();
    }

    private static boolean validChallenge(String challenge) {
        return challenge != null && PKCE_CHALLENGE.matcher(challenge).matches();
    }

    private static boolean verifyPkce(String verifier, String challenge) {
        return verifier != null && PKCE_VERIFIER.matcher(verifier).matches() && validChallenge(challenge)
                && MessageDigest.isEqual(
                Base64.getUrlEncoder().withoutPadding().encode(hash(verifier)),
                challenge.getBytes(StandardCharsets.US_ASCII));
    }

    private static Set<McpScope> scopes(String value) {
        if (!StringUtils.hasText(value)) throw new InvalidOAuthRequest("invalid_scope");
        Set<McpScope> scopes = parseScopes(value);
        if (scopes.isEmpty() || scopeValues(scopes).size() != value.split(" ").length) {
            throw new InvalidOAuthRequest("invalid_scope");
        }
        return scopes;
    }

    private static Set<McpScope> parseScopes(String value) {
        EnumSet<McpScope> scopes = EnumSet.noneOf(McpScope.class);
        if (!StringUtils.hasText(value)) return scopes;
        try {
            for (String item : value.split(" ")) scopes.add(McpScope.fromValue(item));
            return Set.copyOf(scopes);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidOAuthRequest("invalid_scope");
        }
    }

    private static Set<String> scopeValues(Set<McpScope> scopes) {
        return scopes.stream().map(McpScope::value).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String scopeText(Set<McpScope> scopes) {
        return String.join(" ", scopeValues(scopes).stream().sorted().toList());
    }

    private static String opaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    private static String redirectResponse(String redirect, String name, String value, String state) {
        var builder = org.springframework.web.util.UriComponentsBuilder.fromUriString(redirect).queryParam(name, value);
        if (state != null) builder.queryParam("state", state);
        return builder.build().encode().toUriString();
    }

    public record AuthorizationRequest(String responseType, String clientId, String redirectUri, String scope,
                                       String state, String codeChallenge, String codeChallengeMethod,
                                       String resource) {}
    public enum Decision { APPROVE, DENY }
    public record DecisionRequest(Decision decision, String workspaceId) {}
    public record WorkspaceChoice(@JsonProperty("workspace_id") String workspaceId,
                                  @JsonProperty("workspace_name") String workspaceName,
                                  String role, @JsonProperty("policy_generation") long policyGeneration) {}
    public record PendingAuthorization(@JsonProperty("pending_id") UUID pendingId,
                                       @JsonProperty("client_id") String clientId,
                                       @JsonProperty("client_name") Optional<String> clientName,
                                       @JsonProperty("redirect_host") String redirectHost,
                                       @JsonProperty("app_identity_verified") boolean appIdentityVerified,
                                       Set<McpScope> scopes, List<WorkspaceChoice> workspaces,
                                       @JsonProperty("expires_at") Instant expiresAt) {}
    public record AuthorizationDecision(@JsonProperty("redirect_uri") String redirectUri,
                                        @JsonProperty("grant_id") UUID grantId) {}
    public record IssuedTokens(String accessToken, Instant accessIssuedAt, Instant accessExpiresAt,
                               String refreshToken, Instant refreshExpiresAt, Set<String> scopes) {}
    public record RefreshResult(Status status, IssuedTokens tokens) {
        enum Status { SUCCESS, INVALID, REUSED }
        static RefreshResult success(IssuedTokens tokens) { return new RefreshResult(Status.SUCCESS, tokens); }
        static RefreshResult invalid() { return new RefreshResult(Status.INVALID, null); }
        static RefreshResult reused() { return new RefreshResult(Status.REUSED, null); }
    }
    public static final class InvalidOAuthRequest extends RuntimeException {
        private final String error;
        public InvalidOAuthRequest(String error) { super(error); this.error = error; }
        public String error() { return error; }
    }
    private record PendingRow(String userId, String sessionId, String clientId, String redirectUri, String resource, String scopes,
                              String codeChallenge, String state, Instant expiresAt, String decision) {}
    private record CodeRow(UUID grantId, String userId, String workspaceId, String clientId, String redirectUri,
                           String resource, String scopes, String codeChallenge, long policyGeneration,
                           Instant expiresAt, Instant consumedAt) {}
    private record RefreshRow(UUID tokenId, UUID familyId, long sequence, String tokenStatus, Instant expiresAt,
                              UUID grantId, String userId, String workspaceId, String clientId, String resource,
                              String scopes, long policyGeneration, String familyStatus, UUID currentTokenId) {}
    private record RefreshValue(UUID tokenId, String value) {}
}
