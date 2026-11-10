package fruition.access.mcp.service;

import fruition.access.mcp.domain.McpClientIds;
import fruition.access.mcp.domain.McpClientPolicy;
import fruition.access.mcp.domain.McpGrant;
import fruition.access.mcp.domain.McpGrantRevocationReason;
import fruition.access.mcp.domain.McpGrantStatus;
import fruition.access.mcp.domain.McpScope;
import fruition.access.mcp.domain.McpWorkspacePolicy;
import fruition.access.mcp.dto.McpAuthorizationRequest;
import fruition.access.mcp.dto.McpAuthorizationResponse;
import fruition.access.mcp.repository.McpClientPolicyRepository;
import fruition.access.mcp.repository.McpGrantRepository;
import fruition.access.mcp.repository.McpWorkspacePolicyRepository;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class McpAuthorizationService {
    private static final Logger log = LoggerFactory.getLogger(McpAuthorizationService.class);

    private final McpCurrentAuthorizationReader reader;
    private final McpGrantRepository grants;
    private final McpWorkspacePolicyRepository policies;
    private final McpClientPolicyRepository clients;
    private final UserRepository users;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;

    public McpAuthorizationService(McpCurrentAuthorizationReader reader,
                                   McpGrantRepository grants,
                                   McpWorkspacePolicyRepository policies,
                                   McpClientPolicyRepository clients,
                                   UserRepository users,
                                   WorkspaceRepository workspaces,
                                   WorkspaceMemberRepository members) {
        this.reader = reader;
        this.grants = grants;
        this.policies = policies;
        this.clients = clients;
        this.users = users;
        this.workspaces = workspaces;
        this.members = members;
    }

    public McpAuthorizationResponse authorize(UUID grantId, McpAuthorizationRequest request) {
        try {
            return reader.authorize(grantId, request);
        } catch (RuntimeException unavailable) {
            log.error("[MCP current authorization unavailable] grantId={}", grantId, unavailable);
            return McpAuthorizationResponse.redactedDeny(
                    McpAuthorizationResponse.DenyReason.AUTHZ_UNAVAILABLE, Instant.now());
        }
    }

    @Transactional
    public McpClientPolicy setClientEnabled(String clientId, boolean enabled) {
        String exactClientId = McpClientIds.requireValid(clientId);
        Instant now = Instant.now();
        clients.insertDefault(exactClientId);
        McpClientPolicy policy = clients.findForUpdate(exactClientId).orElseThrow();
        boolean changed = policy.changeEnabled(enabled, now);
        if (changed && !enabled) {
            grants.revokeForDisabledClient(exactClientId, now);
        }
        return policy;
    }

    @Transactional
    public McpWorkspacePolicy updateWorkspacePolicy(String workspaceId, boolean enabled,
                                                     Set<McpScope> allowedScopes) {
        requireId(workspaceId, "workspace_id");
        workspaces.findActiveByIdForShare(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("workspace does not exist"));
        Set<McpScope> scopes = copyScopes(allowedScopes, true);
        policies.insertDefault(workspaceId);
        McpWorkspacePolicy policy = policies.findForUpdate(workspaceId).orElseThrow();
        Instant now = Instant.now();
        boolean changed = policy.update(enabled, scopes, now);
        if (changed) {
            if (enabled) {
                grants.revokeForChangedWorkspace(workspaceId, now);
            } else {
                grants.revokeForDisabledWorkspace(workspaceId, now);
            }
        }
        return policy;
    }

    @Transactional
    public McpGrant createGrant(String userId, String workspaceId, String clientId, Set<McpScope> scopes) {
        return createGrant(userId, workspaceId, clientId, scopes, false);
    }

    /**
     * OAuth consent grant: grants the requested scopes the locked workspace policy still allows.
     * Never grants a scope that was not requested; fails when nothing requested is allowed.
     */
    @Transactional
    public McpGrant createConsentGrant(String userId, String workspaceId, String clientId,
                                       Set<McpScope> requestedScopes) {
        return createGrant(userId, workspaceId, clientId, requestedScopes, true);
    }

    private McpGrant createGrant(String userId, String workspaceId, String clientId, Set<McpScope> scopes,
                                 boolean narrowToPolicy) {
        requireId(userId, "user_id");
        requireId(workspaceId, "workspace_id");
        String exactClientId = McpClientIds.requireValid(clientId);
        Set<McpScope> requestedScopes = copyScopes(scopes, false);
        McpClientPolicy client = clients.findForUpdate(exactClientId)
                .filter(McpClientPolicy::isEnabled)
                .orElseThrow(() -> new IllegalStateException("MCP client is disabled"));
        users.findByIdForShare(userId)
                .orElseThrow(() -> new IllegalStateException("user does not exist"));
        workspaces.findActiveByIdForShare(workspaceId)
                .orElseThrow(() -> new IllegalStateException("workspace does not exist"));
        if (members.findActiveMemberForShare(workspaceId, userId).isEmpty()) {
            throw new IllegalStateException("user is not an active workspace member");
        }
        McpWorkspacePolicy policy = policies.findForUpdate(workspaceId)
                .filter(McpWorkspacePolicy::isEnabled)
                .orElseThrow(() -> new IllegalStateException("workspace MCP policy is disabled"));
        Set<McpScope> grantedScopes = requestedScopes;
        if (narrowToPolicy) {
            grantedScopes = EnumSet.copyOf(requestedScopes);
            grantedScopes.retainAll(policy.getAllowedScopes());
            if (grantedScopes.isEmpty()) {
                throw new IllegalArgumentException("no requested scope is allowed by workspace policy");
            }
        } else if (!policy.getAllowedScopes().containsAll(requestedScopes)) {
            throw new IllegalArgumentException("grant scopes exceed workspace policy");
        }
        return grants.saveAndFlush(new McpGrant(UUID.randomUUID(), userId, workspaceId, client.getClientId(),
                policy.getGeneration(), grantedScopes, Instant.now()));
    }

    @Transactional
    public McpGrant revokeGrant(UUID grantId, McpGrantRevocationReason reason) {
        McpGrant grant = grants.findForUpdate(grantId)
                .orElseThrow(() -> new IllegalArgumentException("grant does not exist"));
        grant.revoke(reason, Instant.now());
        return grant;
    }

    @Transactional
    public boolean disconnectOwnGrant(String userId, UUID grantId) {
        requireId(userId, "user_id");
        McpGrantRepository.GrantContext snapshot = grants.findContextById(grantId)
                .filter(grant -> grant.getUserId().equals(userId))
                .orElse(null);
        if (snapshot == null) {
            return false;
        }
        LockedCredentialContext context = lockCredentialContext(grantId, snapshot.getUserId(),
                snapshot.getWorkspaceId(), snapshot.getClientId());
        context.grant().revoke(McpGrantRevocationReason.USER_DISCONNECT, Instant.now());
        return true;
    }

    public void lockPendingDecision(String userId, String clientId) {
        clients.findForUpdate(clientId);
        users.findByIdForShare(userId)
                .orElseThrow(() -> new IllegalStateException("user does not exist"));
    }

    /** Locks credential context in the same order as grant creation and identity purge. */
    public LockedCredentialContext lockCredentialContext(UUID grantId, String userId, String workspaceId,
                                                          String clientId) {
        Optional<McpClientPolicy> client = clients.findForUpdate(clientId);
        boolean userPresent = users.findByIdForShare(userId).isPresent();
        boolean workspacePresent = workspaces.findActiveByIdForShare(workspaceId).isPresent();
        boolean memberPresent = members.findActiveMemberForShare(workspaceId, userId).isPresent();
        Optional<McpWorkspacePolicy> policy = policies.findForUpdate(workspaceId);
        McpGrant grant = grants.findForUpdate(grantId)
                .orElseThrow(() -> new IllegalStateException("MCP grant is unavailable"));
        if (!grant.getUserId().equals(userId) || !grant.getWorkspaceId().equals(workspaceId)
                || !grant.getClientId().equals(clientId)) {
            throw new IllegalStateException("MCP grant is unavailable");
        }
        return new LockedCredentialContext(grant,
                client.filter(McpClientPolicy::isEnabled).isPresent(), userPresent, workspacePresent, memberPresent,
                policy.filter(McpWorkspacePolicy::isEnabled).map(McpWorkspacePolicy::getGeneration).orElse(0L),
                policy.filter(McpWorkspacePolicy::isEnabled).map(McpWorkspacePolicy::getAllowedScopes).orElse(Set.of()));
    }

    /** Rechecks every locked current authorization fence immediately before issuing a credential. */
    public McpGrant requireCredentialIssuance(LockedCredentialContext context, Set<McpScope> scopes,
                                              long policyGeneration) {
        McpGrant grant = context.grant();
        if (!context.clientEnabled() || !context.userPresent() || !context.workspacePresent()
                || !context.memberPresent() || context.policyGeneration() != policyGeneration
                || !context.policyScopes().containsAll(scopes) || grant.getStatus() != McpGrantStatus.ACTIVE
                || grant.getPolicyGeneration() != policyGeneration || !grant.getScopes().equals(scopes)) {
            throw new IllegalStateException("MCP authorization is unavailable");
        }
        return grant;
    }

    public void revokeCredentialGrant(LockedCredentialContext context, McpGrantRevocationReason reason) {
        context.grant().revoke(reason, Instant.now());
    }

    public record LockedCredentialContext(McpGrant grant, boolean clientEnabled, boolean userPresent,
                                          boolean workspacePresent, boolean memberPresent, long policyGeneration,
                                          Set<McpScope> policyScopes) {}

    private static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static Set<McpScope> copyScopes(Set<McpScope> scopes, boolean emptyAllowed) {
        if (scopes == null || (!emptyAllowed && scopes.isEmpty())) {
            throw new IllegalArgumentException("scopes are required");
        }
        return scopes.isEmpty() ? EnumSet.noneOf(McpScope.class) : EnumSet.copyOf(scopes);
    }
}
