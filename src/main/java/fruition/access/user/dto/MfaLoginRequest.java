package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaLoginRequest(
        @JsonProperty("mfa_token")
        @NotBlank(message = "{validation.mfa_token.required}")
        @Schema(description = "POST /api/auth/login이 돌려준 1회용 토큰")
        String mfaToken,

        @NotBlank(message = "{validation.code.required}")
        @Size(max = 64, message = "{validation.code.max64}")
        @Schema(description = "인증 앱의 6자리 코드 또는 복구 코드", example = "482917")
        String code
) {}
