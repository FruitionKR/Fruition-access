package fruition.access.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.access.mcp.domain.McpGrant;
import fruition.access.mcp.domain.McpGrantRevocationReason;
import fruition.access.mcp.domain.McpGrantStatus;
import fruition.access.mcp.domain.McpScope;
import fruition.access.mcp.domain.McpWorkspacePolicy;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspacePermission;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record McpAuthorizationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Decision decision,
        @JsonProperty("deny_reason")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DenyReason denyReason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) GrantView grant,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PolicyView policy,
        @JsonProperty("current_access")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        CurrentAccessView currentAccess,
        @JsonProperty("effective_scopes")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<McpScope> effectiveScopes,
        @JsonProperty("client_name") String clientName,
        @JsonProperty("checked_at")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant checkedAt) {

    public static McpAuthorizationResponse allow(GrantView grant, PolicyView policy,
                                                  CurrentAccessView access, Set<McpScope> scopes,
                                                  String clientName, Instant now) {
        return new McpAuthorizationResponse(Decision.ALLOW, null, grant, policy, access, scopes, clientName, now);
    }

    public static McpAuthorizationResponse deny(DenyReason reason, GrantView grant, PolicyView policy,
                                                 CurrentAccessView access, Set<McpScope> scopes, Instant now) {
        return new McpAuthorizationResponse(Decision.DENY, reason, grant, policy, access, scopes, null, now);
    }

    public static McpAuthorizationResponse redactedDeny(DenyReason reason, Instant now) {
        return deny(reason, null, null, null, Set.of(), now);
    }

    public static GrantView grant(McpGrant grant) {
        return new GrantView(grant.getGrantId(), grant.getStatus(), grant.getRevocationReason(), grant.getUserId(),
                grant.getWorkspaceId(), grant.getClientId(), grant.getScopes());
    }

    public static PolicyView policy(McpWorkspacePolicy policy) {
        return new PolicyView(policy.isEnabled(), policy.getGeneration(), policy.getAllowedScopes());
    }

    public static CurrentAccessView access(WorkspaceMember member) {
        return member == null
                ? new CurrentAccessView(CurrentRole.NONE, Set.of())
                : new CurrentAccessView(CurrentRole.valueOf(member.getRole().name()), member.effectivePermissions());
    }

    public enum Decision { ALLOW, DENY }

    public enum DenyReason {
        TOKEN_CONTEXT_MISMATCH,
        GRANT_INACTIVE,
        POLICY_DISABLED,
        POLICY_GENERATION_MISMATCH,
        NOT_MEMBER,
        PERMISSION_REVOKED,
        INSUFFICIENT_SCOPE,
        CLIENT_DISABLED,
        PURGED,
        AUTHZ_UNAVAILABLE
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record GrantView(
            @JsonProperty("grant_id") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID grantId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpGrantStatus status,
            @JsonProperty("revocation_reason")
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            McpGrantRevocationReason revocationReason,
            @JsonProperty("user_id") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String userId,
            @JsonProperty("workspace_id") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workspaceId,
            @JsonProperty("client_id") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<McpScope> scopes) {}

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record PolicyView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long generation,
            @JsonProperty("allowed_scopes")
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<McpScope> allowedScopes) {}

    public enum CurrentRole { OWNER, MEMBER, NONE }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CurrentAccessView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CurrentRole role,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<WorkspacePermission> permissions) {}
}
