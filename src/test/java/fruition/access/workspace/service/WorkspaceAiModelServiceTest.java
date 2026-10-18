package fruition.access.workspace.service;

import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.dto.WorkspaceAiModelRequest;
import fruition.access.workspace.repository.WorkspaceRepository;
import fruition.shared.ai.AiModelCatalog;
import fruition.shared.ai.InvalidAiModelException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceAiModelServiceTest {
    @Mock WorkspaceRepository workspaceRepository;

    private WorkspaceAiModelService service;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service = new WorkspaceAiModelService(
                workspaceRepository, new AiModelCatalog("openai,gemini,claude"));
        workspace = new Workspace("ws_1", "테스트");
    }

    @Test
    void getInternal_returnsDefaultSetting() {
        when(workspaceRepository.findById("ws_1")).thenReturn(Optional.of(workspace));

        var response = service.getInternal("ws_1");

        assertThat(response.ingestLint().provider()).isEqualTo("gemini");
        assertThat(response.ingestLint().model()).isEqualTo("gemini-3.5-flash-lite");
    }

    @Test
    void updateInternal_selectsEnabledModel() {
        when(workspaceRepository.findById("ws_1")).thenReturn(Optional.of(workspace));

        var response = service.updateInternal("ws_1", new WorkspaceAiModelRequest(
                new WorkspaceAiModelRequest.AiModelSelection("openai", "gpt-6-luna")));

        assertThat(response.ingestLint().provider()).isEqualTo("openai");
        assertThat(response.ingestLint().model()).isEqualTo("gpt-6-luna");
    }

    @Test
    void updateInternal_rejectsClaudeForSharedSetting() {
        when(workspaceRepository.findById("ws_1")).thenReturn(Optional.of(workspace));

        // Claude는 카탈로그에 있어도 워크스페이스 공용 설정에는 저장할 수 없다(채팅에서 직접 고를 때만).
        assertThatThrownBy(() -> service.updateInternal("ws_1", new WorkspaceAiModelRequest(
                new WorkspaceAiModelRequest.AiModelSelection("claude", "claude-haiku-4-5-20251001"))))
                .isInstanceOf(InvalidAiModelException.class)
                .hasMessageContaining("Claude 모델은 채팅에서 직접 선택할 때만");
        assertThat(workspace.getIngestLintProvider()).isEqualTo("gemini");
    }

    @Test
    void updateInternal_rejectsModelsOutsideCatalog() {
        when(workspaceRepository.findById("ws_1")).thenReturn(Optional.of(workspace));

        assertThatThrownBy(() -> service.updateInternal("ws_1", new WorkspaceAiModelRequest(
                new WorkspaceAiModelRequest.AiModelSelection("openai", "gpt-6.1-sol"))))
                .isInstanceOf(InvalidAiModelException.class);
    }
}
