package fruition.access.mcp.service;

import fruition.access.mcp.domain.McpGrant;
import fruition.access.mcp.dto.McpConnectionListResponse;
import fruition.access.mcp.repository.McpGrantRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class McpConnectionService {
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("grantId"));

    private final McpGrantRepository grants;
    private final McpAuthorizationService authorization;

    public McpConnectionService(McpGrantRepository grants, McpAuthorizationService authorization) {
        this.grants = grants;
        this.authorization = authorization;
    }

    @Transactional(readOnly = true)
    public McpConnectionListResponse list(String userId, int page, int size) {
        var result = grants.findByUserId(userId, PageRequest.of(page, size, NEWEST_FIRST));
        return new McpConnectionListResponse(result.getContent().stream().map(McpConnectionService::response).toList(),
                page, size, result.hasNext());
    }

    public boolean disconnect(String userId, UUID grantId) {
        return authorization.disconnectOwnGrant(userId, grantId);
    }

    private static McpConnectionListResponse.Connection response(McpGrant grant) {
        return new McpConnectionListResponse.Connection(grant.getGrantId(), grant.getWorkspaceId(),
                grant.getClientId(), grant.getScopes().stream().sorted().toList(), grant.getStatus(),
                grant.getRevocationReason(), grant.getCreatedAt(), grant.getRevokedAt());
    }
}
