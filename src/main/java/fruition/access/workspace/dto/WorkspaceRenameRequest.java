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
        @Schema(description = "하위호환용 옛 필드. 보내면 auto_ingest_markdown_on_upload와 auto_ingest_pdf_on_convert를 모두 이 값으로 바꾼다. "
                + "새 필드와 함께 오면 새 필드가 우선한다. 생략하면 바꾸지 않는다.", example = "false", nullable = true)
        Boolean autoIngestOnUpload,

        @JsonProperty("auto_ingest_markdown_on_upload")
        @Schema(description = "Markdown·txt 업로드 직후 자동으로 위키에 편입할지 여부. 생략하면 바꾸지 않는다.",
                example = "true", nullable = true)
        Boolean autoIngestMarkdownOnUpload,

        @JsonProperty("auto_ingest_pdf_on_convert")
        @Schema(description = "PDF 변환이 끝난 뒤 자동으로 위키에 편입할지 여부. 생략하면 바꾸지 않는다.",
                example = "false", nullable = true)
        Boolean autoIngestPdfOnConvert
) {
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "name은 필수입니다.")
    public boolean isNameNotBlank() {
        return name == null || !name.isBlank();
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "name, auto_ingest_markdown_on_upload, auto_ingest_pdf_on_convert, auto_ingest_on_upload 중 하나는 필수입니다.")
    public boolean isNotEmpty() {
        return name != null || autoIngestOnUpload != null
                || autoIngestMarkdownOnUpload != null || autoIngestPdfOnConvert != null;
    }
}
