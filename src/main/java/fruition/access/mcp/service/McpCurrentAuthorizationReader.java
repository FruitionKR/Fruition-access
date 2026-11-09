package fruition.access.mcp.service;

import fruition.access.mcp.domain.McpClientPolicy;
import fruition.access.mcp.domain.McpGrant;
import fruition.access.mcp.domain.McpGrantRevocationReason;
import fruition.access.mcp.domain.McpGrantStatus;
import fruition.access.mcp.domain.McpScope;
import fruition.access.mcp.domain.McpWorkspacePolicy;
import fruition.access.mcp.dto.McpAuthorizationRequest;
import fruition.access.mcp.dto.McpAuthorizationResponse;
import fruition.access.mcp.oauth.McpClientMetadataResolver;
import fruition.access.mcp.repository.McpClientPolicyRepository;
import fruition.access.mcp.repository.McpGrantRepository;
import fruition.access.mcp.repository.McpWorkspacePolicyRepository;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static fruition.access.mcp.dto.McpAuthorizationResponse.DenyReason;

@Service
public class McpCurrentAuthorizationReader {
    private final McpGrantRepository grants;
    private final McpWorkspacePolicyRepository policies;
    private final McpClientPolicyRepository clients;
    private final WorkspaceMemberRepository members;
    private final McpClientMetadataResolver clientMetadata;

    public McpCurrentAuthorizationReader(McpGrantRepository grants,
                                         McpWorkspacePolicyRepository policies,
                                         McpClientPolicyRepository clients,
                                         WorkspaceMemberRepository members,
                                         McpClientMetadataResolver clientMetadata) {
        this.grants = grants;
        this.policies = policies;
        this.clients = clients;
        this.members = members;
        this.clientMetadata = clientMetadata;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public McpAuthorizationResponse authorize(UUID grantId, McpAuthorizationRequest request) {
        Instant checkedAt = Instant.now();
        McpGrant grant = grants.findById(grantId).orElse(null);
        if (grant == null) {
            return McpAuthorizationResponse.redactedDeny(DenyReason.GRANT_INACTIVE, checkedAt);
        }
        if (!contextMatches(grant, request.tokenContext(), request.action())) {
            return McpAuthorizationResponse.redactedDeny(DenyReason.TOKEN_CONTEXT_MISMATCH, checkedAt);
        }

        McpWorkspacePolicy policy = policies.findById(grant.getWorkspaceId()).orElse(null);
        McpClientPolicy client = clients.findById(grant.getClientId()).orElse(null);
        WorkspaceMember member = members.findActiveMember(grant.getWorkspaceId(), grant.getUserId()).orElse(null);
        Set<McpScope> effectiveScopes = effectiveScopes(request.tokenContext().scopes(), grant, policy);
        var grantView = McpAuthorizationResponse.grant(grant);
        var policyView = policy == null ? null : McpAuthorizationResponse.policy(policy);
        var accessView = McpAuthorizationResponse.access(member);

        DenyReason denied = denial(grant, policy, client, member, effectiveScopes, request.action());
        String clientName = denied == null && request.action().phase() == McpAuthorizationRequest.Phase.WEB_APPROVAL
                ? clientMetadata.resolve(grant.getClientId()).displayName()
                .filter(name -> !name.isBlank())
                .orElseThrow(() -> new IllegalStateException("MCP client name is unavailable"))
                : null;
        return denied == null
                ? McpAuthorizationResponse.allow(grantView, policyView, accessView, effectiveScopes, clientName, checkedAt)
                : McpAuthorizationResponse.deny(denied, grantView, policyView, accessView, effectiveScopes, checkedAt);
    }

    private static boolean contextMatches(McpGrant grant, McpAuthorizationRequest.TokenContext context,
                                          McpAuthorizationRequest.Action action) {
        boolean exactScopes = grant.getScopes().equals(context.scopes());
        boolean serviceActorScopes = !context.scopes().isEmpty()
                && grant.getScopes().containsAll(context.scopes())
                && (action.phase() == McpAuthorizationRequest.Phase.PROPOSAL_CREATE
                || action.phase() == McpAuthorizationRequest.Phase.WEB_APPROVAL
                || (action.phase() == McpAuthorizationRequest.Phase.OPERATION_BOUNDARY
                && action.executionBasis() == McpAuthorizationRequest.ExecutionBasis.EXPLICIT_WEB_APPROVAL));
        return grant.getUserId().equals(context.subject())
                && grant.getWorkspaceId().equals(context.workspaceId())
                && grant.getClientId().equals(context.clientId())
                && grant.getPolicyGeneration() == context.policyGeneration()
                && (exactScopes || serviceActorScopes);
    }

    private static Set<McpScope> effectiveScopes(Set<McpScope> tokenScopes, McpGrant grant,
                                                  McpWorkspacePolicy policy) {
        if (policy == null) {
            return Set.of();
        }
        EnumSet<McpScope> effective = EnumSet.copyOf(tokenScopes);
        effective.retainAll(grant.getScopes());
        effective.retainAll(policy.getAllowedScopes());
        return Set.copyOf(effective);
    }

    private static DenyReason denial(McpGrant grant, McpWorkspacePolicy policy, McpClientPolicy client,
                                     WorkspaceMember member, Set<McpScope> effectiveScopes,
                                     McpAuthorizationRequest.Action action) {
        if (grant.getStatus() == McpGrantStatus.PURGED) {
            return DenyReason.PURGED;
        }
        if (grant.getStatus() == McpGrantStatus.SECURITY_REVOKED) {
            return grant.getRevocationReason() == McpGrantRevocationReason.CLIENT_DISABLED
                    ? DenyReason.CLIENT_DISABLED : DenyReason.GRANT_INACTIVE;
        }
        if (grant.getStatus() == McpGrantStatus.ADMIN_REVOKED) {
            return grant.getRevocationReason() == McpGrantRevocationReason.WORKSPACE_POLICY_DISABLED
                    ? DenyReason.POLICY_DISABLED : DenyReason.GRANT_INACTIVE;
        }
        if (client == null || !client.isEnabled()) {
            return DenyReason.CLIENT_DISABLED;
        }
        if (policy == null || !policy.isEnabled()) {
            return DenyReason.POLICY_DISABLED;
        }
        if (policy.getGeneration() != grant.getPolicyGeneration()) {
            return DenyReason.POLICY_GENERATION_MISMATCH;
        }
        if (action.executionBasis() == McpAuthorizationRequest.ExecutionBasis.EXPLICIT_WEB_APPROVAL
                && action.approvedPolicyGeneration() != policy.getGeneration()) {
            return DenyReason.POLICY_GENERATION_MISMATCH;
        }
        if (grant.getStatus() == McpGrantStatus.USER_DISCONNECTED && !disconnectException(action, policy)) {
            return DenyReason.GRANT_INACTIVE;
        }
        if (grant.getStatus() != McpGrantStatus.ACTIVE
                && grant.getStatus() != McpGrantStatus.USER_DISCONNECTED) {
            return DenyReason.GRANT_INACTIVE;
        }
        if (member == null) {
            return DenyReason.NOT_MEMBER;
        }
        if (!member.effectivePermissions().containsAll(action.requiredPermissions())) {
            return DenyReason.PERMISSION_REVOKED;
        }
        if (!effectiveScopes.containsAll(action.requiredScopes())) {
            return DenyReason.INSUFFICIENT_SCOPE;
        }
        return null;
    }

    private static boolean disconnectException(McpAuthorizationRequest.Action action, McpWorkspacePolicy policy) {
        return action.phase() == McpAuthorizationRequest.Phase.OPERATION_BOUNDARY
                && action.executionBasis() == McpAuthorizationRequest.ExecutionBasis.EXPLICIT_WEB_APPROVAL
                && action.approvedPolicyGeneration() != null
                && action.approvedPolicyGeneration() == policy.getGeneration();
    }
}
