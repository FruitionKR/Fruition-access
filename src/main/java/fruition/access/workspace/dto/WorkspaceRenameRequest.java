package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

public record WorkspaceRenameRequest(
        @Size(max = 255, message = "name은 255자 이하여야 합니다.")
        @Schema(description = "새 워크스페이스 이름(255자 이하, 공백만으로는 불가). 생략하면 바꾸지 않는다.",
                example = "이름 바꾼 워크스페이스", nullable = true)
        String name,

        @JsonProperty("auto_ingest_on_upload")
        @Schema(description = "업로드 시 자동으로 ingest할지 여부. 생략하면 바꾸지 않는다.", example = "false", nullable = true)
        Boolean autoIngestOnUpload
) {
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "name은 필수입니다.")
    public boolean isNameNotBlank() {
        return name == null || !name.isBlank();
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "name 또는 auto_ingest_on_upload 중 하나는 필수입니다.")
    public boolean isNotEmpty() {
        return name != null || autoIngestOnUpload != null;
    }
}
