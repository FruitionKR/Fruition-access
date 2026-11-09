package fruition.access.mcp.service;

import fruition.access.mcp.domain.McpGrant;
import fruition.access.mcp.domain.McpGrantRevocationReason;
import fruition.access.mcp.dto.McpWorkspaceConnectionListResponse;
import fruition.access.mcp.repository.McpGrantRepository;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.exception.WorkspaceAccessDeniedException;
import fruition.access.workspace.exception.WorkspaceNotFoundException;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class McpWorkspaceConnectionManagementService {
    private static final Logger log = LoggerFactory.getLogger(McpWorkspaceConnectionManagementService.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("grantId"));

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final McpGrantRepository grants;

    public McpWorkspaceConnectionManagementService(WorkspaceRepository workspaces,
                                                   WorkspaceMemberRepository members,
                                                   McpGrantRepository grants) {
        this.workspaces = workspaces;
        this.members = members;
        this.grants = grants;
    }

    @Transactional
    public McpWorkspaceConnectionListResponse list(String actorId, String workspaceId, int page, int size) {
        requireOwner(actorId, workspaceId);
        var result = grants.findByWorkspaceId(workspaceId, PageRequest.of(page, size, NEWEST_FIRST));
        return new McpWorkspaceConnectionListResponse(result.getContent().stream()
                .map(McpWorkspaceConnectionManagementService::response).toList(), page, size, result.hasNext());
    }

    @Transactional
    public boolean revoke(String actorId, String workspaceId, UUID grantId) {
        requireOwner(actorId, workspaceId);
        McpGrant grant = grants.findInWorkspaceForUpdate(workspaceId, grantId).orElse(null);
        if (grant == null) {
            audit(actorId, workspaceId, grantId, "NOT_FOUND");
            return false;
        }
        boolean changed = grant.revoke(McpGrantRevocationReason.ADMIN_GRANT_REVOKE, Instant.now());
        audit(actorId, workspaceId, grantId, changed ? "REVOKED" : "ALREADY_REVOKED");
        return true;
    }

    private void requireOwner(String actorId, String workspaceId) {
        workspaces.findActiveByIdForShare(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
        WorkspaceMember member = members.findActiveMemberForShare(workspaceId, actorId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
        if (member.getRole() != WorkspaceRole.OWNER) {
            throw new WorkspaceAccessDeniedException("error.WORKSPACE_ACCESS_DENIED.manage_mcp_connections",
                    "MCP 연결을 관리하려면 OWNER 권한이 필요합니다.");
        }
    }

    private static McpWorkspaceConnectionListResponse.Connection response(McpGrant grant) {
        return new McpWorkspaceConnectionListResponse.Connection(grant.getGrantId(), grant.getUserId(),
                grant.getWorkspaceId(), grant.getClientId(), grant.getScopes().stream().sorted().toList(),
                grant.getStatus(), grant.getRevocationReason(), grant.getCreatedAt(), grant.getRevokedAt());
    }

    private static void audit(String actorId, String workspaceId, UUID grantId, String result) {
        log.info("[MCP grant administrative revoke] actorId={} workspaceId={} grantId={} "
                        + "action=ADMIN_GRANT_REVOKE result={}", actorId, workspaceId, grantId, result);
    }
}
