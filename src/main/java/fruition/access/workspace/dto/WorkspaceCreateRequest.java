package fruition.access.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record WorkspaceCreateRequest(
        @Size(max = 255, message = "name은 255자 이하여야 합니다.")
        @Schema(description = "워크스페이스 이름(255자 이하). 비워 두면 \"새 워크스페이스\"가 된다. 이미 같은 이름이 있으면 뒤에 번호가 붙는다.",
                example = "내 워크스페이스")
        String name
) {}
