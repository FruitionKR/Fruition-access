package fruition.access.workspace.dto;

import fruition.access.workspace.domain.WorkspaceRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WorkspaceInvitationCreateRequest(
        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.format.invitation}")
        @Size(max = 255, message = "{validation.email.max255}")
        @Schema(description = "초대할 이메일 주소", example = "member@example.com")
        String email,

        @NotNull(message = "{validation.role.required}")
        @Schema(description = "수락 시 부여할 역할", example = "MEMBER", allowableValues = {"OWNER", "MEMBER"})
        WorkspaceRole role
) {}
