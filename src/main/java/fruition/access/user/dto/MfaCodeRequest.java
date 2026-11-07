package fruition.access.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaCodeRequest(
        @NotBlank(message = "{validation.code.required}")
        @Size(max = 64, message = "{validation.code.max64}")
        @Schema(description = "인증 앱의 6자리 코드 또는 복구 코드", example = "482917")
        String code
) {}
