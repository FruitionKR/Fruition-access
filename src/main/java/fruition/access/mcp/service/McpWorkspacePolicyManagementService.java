package fruition.access.mcp.service;

import fruition.access.mcp.domain.McpWorkspacePolicy;
import fruition.access.mcp.dto.McpWorkspacePolicyRequest;
import fruition.access.mcp.dto.McpWorkspacePolicyResponse;
import fruition.access.mcp.repository.McpWorkspacePolicyRepository;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.exception.WorkspaceAccessDeniedException;
import fruition.access.workspace.exception.WorkspaceNotFoundException;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class McpWorkspacePolicyManagementService {
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final McpWorkspacePolicyRepository policies;
    private final McpAuthorizationService authorization;

    public McpWorkspacePolicyManagementService(WorkspaceRepository workspaces,
                                               WorkspaceMemberRepository members,
                                               McpWorkspacePolicyRepository policies,
                                               McpAuthorizationService authorization) {
        this.workspaces = workspaces;
        this.members = members;
        this.policies = policies;
        this.authorization = authorization;
    }

    @Transactional
    public McpWorkspacePolicyResponse get(String actorId, String workspaceId) {
        requireOwner(actorId, workspaceId);
        return policies.findById(workspaceId)
                .map(McpWorkspacePolicyManagementService::response)
                .orElseGet(() -> new McpWorkspacePolicyResponse(
                        workspaceId, false, List.of(), null, null));
    }

    @Transactional
    public McpWorkspacePolicyResponse update(String actorId, String workspaceId,
                                             McpWorkspacePolicyRequest request) {
        requireOwner(actorId, workspaceId);
        return response(authorization.updateWorkspacePolicy(
                workspaceId, request.enabled(), request.allowedScopes()));
    }

    private void requireOwner(String actorId, String workspaceId) {
        workspaces.findActiveByIdForShare(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
        WorkspaceMember member = members.findActiveMemberForShare(workspaceId, actorId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
        if (member.getRole() != WorkspaceRole.OWNER) {
            throw new WorkspaceAccessDeniedException("error.WORKSPACE_ACCESS_DENIED.manage_mcp_policy",
                    "MCP 정책을 관리하려면 OWNER 권한이 필요합니다.");
        }
    }

    private static McpWorkspacePolicyResponse response(McpWorkspacePolicy policy) {
        return new McpWorkspacePolicyResponse(policy.getWorkspaceId(), policy.isEnabled(),
                policy.getAllowedScopes().stream().sorted().toList(),
                policy.getGeneration(), policy.getUpdatedAt());
    }
}
