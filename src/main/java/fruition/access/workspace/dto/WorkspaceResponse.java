package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.access.workspace.domain.Workspace;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record WorkspaceResponse(
        @Schema(description = "워크스페이스 ID. 이후 대부분의 API 경로에 들어간다.",
                example = "ws_9d47a0e9a6324341b47562553b75f92a")
        String id,

        @Schema(description = "워크스페이스 이름", example = "내 워크스페이스")
        String name,

        @JsonProperty("icon_emoji")
        @Schema(description = "아이콘 이모지. 설정하지 않았으면 null이다.", example = "📁", nullable = true)
        String iconEmoji,

        @JsonProperty("icon_url")
        @Schema(description = "아이콘 이미지 조회 경로. 이미지를 설정하지 않았으면 null이다.",
                example = "/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/icon/image", nullable = true)
        String iconUrl,

        @JsonProperty("auto_ingest_on_upload")
        @Schema(description = "하위호환용 옛 필드. 값은 auto_ingest_pdf_on_convert와 같다.", example = "false",
                deprecated = true)
        boolean autoIngestOnUpload,

        @JsonProperty("auto_ingest_markdown_on_upload")
        @Schema(description = "Markdown·txt 업로드 직후 자동으로 위키에 편입할지 여부(기본 true)", example = "true")
        boolean autoIngestMarkdownOnUpload,

        @JsonProperty("auto_ingest_pdf_on_convert")
        @Schema(description = "PDF 변환이 끝난 뒤 자동으로 위키에 편입할지 여부(기본 false)", example = "false")
        boolean autoIngestPdfOnConvert,

        @JsonProperty("describe_pdf_figures")
        @Schema(description = "PDF 변환 때 그림을 외부 AI(워크스페이스 AI 모델)로 보내 설명을 붙일지 여부. 기본은 꺼짐이다.",
                example = "false")
        boolean describePdfFigures,

        @JsonProperty("created_at")
        @Schema(description = "생성 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant createdAt,

        @JsonProperty("updated_at")
        @Schema(description = "마지막 변경 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant updatedAt
) {
    public static WorkspaceResponse from(Workspace workspace) {
        return new WorkspaceResponse(
                workspace.getId(),
                workspace.getName(),
                workspace.getIconEmoji(),
                workspace.hasIconImage() ? "/api/workspaces/" + workspace.getId() + "/icon/image" : null,
                workspace.isAutoIngestPdfOnConvert(),
                workspace.isAutoIngestMarkdownOnUpload(),
                workspace.isAutoIngestPdfOnConvert(),
                workspace.isDescribePdfFigures(),
                workspace.getCreatedAt(),
                workspace.getUpdatedAt());
    }
}
