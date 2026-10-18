package fruition.access.workspace.service;

import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.dto.WorkspaceAiModelRequest;
import fruition.access.workspace.dto.WorkspaceAiModelResponse;
import fruition.access.workspace.exception.WorkspaceNotFoundException;
import fruition.access.workspace.repository.WorkspaceRepository;
import fruition.shared.ai.AiModelCatalog;
import fruition.shared.ai.InvalidAiModelException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class WorkspaceAiModelService {
    // ingest_lint는 ingest·wiki lint·스킬·회의록·변환 repair가 함께 쓰는 공용 설정이다.
    // Claude는 사용자가 요청마다 직접 고르는 채팅·Agent에서만 허용한다(2026-10-18 비용 정책).
    private static final Set<String> INGEST_LINT_PROVIDERS = Set.of("openai", "gemini");

    private final WorkspaceRepository workspaceRepository;
    private final AiModelCatalog catalog;

    public WorkspaceAiModelService(WorkspaceRepository workspaceRepository,
                                   AiModelCatalog catalog) {
        this.workspaceRepository = workspaceRepository;
        this.catalog = catalog;
    }

    @Transactional
    public WorkspaceAiModelResponse updateInternal(String workspaceId, WorkspaceAiModelRequest request) {
        Workspace workspace = findActive(workspaceId);
        AiModelCatalog.AiModel selected = catalog.resolve(
                request.ingestLint().provider(), request.ingestLint().model());
        if (!INGEST_LINT_PROVIDERS.contains(selected.provider())) {
            throw new InvalidAiModelException("Claude 모델은 채팅에서 직접 선택할 때만 사용할 수 있습니다. 워크스페이스 설정에는 OpenAI 또는 Gemini 모델을 선택하세요.");
        }
        workspace.changeIngestLintModel(selected.provider(), selected.model());
        return WorkspaceAiModelResponse.from(workspace);
    }

    @Transactional(readOnly = true)
    public WorkspaceAiModelResponse getInternal(String workspaceId) {
        return WorkspaceAiModelResponse.from(findActive(workspaceId));
    }

    private Workspace findActive(String workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .filter(workspace -> workspace.getDeletedAt() == null)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
    }
}
