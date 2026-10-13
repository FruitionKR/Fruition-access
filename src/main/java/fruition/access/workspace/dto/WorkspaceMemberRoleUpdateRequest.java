package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import fruition.access.workspace.domain.WorkspacePermission;
import fruition.access.workspace.domain.WorkspaceRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.util.HashSet;
import java.util.List;

public record WorkspaceMemberRoleUpdateRequest(
        @Schema(description = "새 역할. 생략하면 바꾸지 않는다.", example = "MEMBER",
                allowableValues = {"OWNER", "MEMBER"}, nullable = true)
        WorkspaceRole role,

        @Schema(description = "MEMBER의 권한 항목 전체(교체). 값: wiki.ingest, wiki.lint, skill.author, wiki.restore, document.convert. 생략하면 바꾸지 않는다."
                + " 결과 역할이 OWNER이면 보낼 수 없다(400 OWNER_PERMISSIONS_FIXED).",
                example = "[\"skill.author\", \"wiki.ingest\"]", nullable = true)
        List<@NotNull(message = "permissions에 null을 넣을 수 없습니다.") WorkspacePermission> permissions
) {
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "role 또는 permissions 중 하나는 필수입니다.")
    public boolean isNotEmpty() {
        return role != null || permissions != null;
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "permissions에 중복 항목이 있습니다.")
    public boolean isPermissionsDistinct() {
        return permissions == null || new HashSet<>(permissions).size() == permissions.size();
    }
}
