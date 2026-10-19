package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifiedPasswordChangeRequest(
        @JsonProperty("new_password")
        @NotBlank(message = "new_password는 필수입니다.")
        @Size(min = 8, max = 72, message = "new_password는 8자 이상 72자 이하여야 합니다.")
        @Schema(description = "새 비밀번호(8~72자). 성공하면 현재 세션을 제외한 refresh token이 폐기된다.",
                example = "newPassword1234")
        String newPassword,

        @JsonProperty("verification_token")
        @NotBlank(message = "verification_token은 필수입니다.")
        @Schema(description = "POST /api/auth/me/password/verification으로 받은 인증번호를 확인해 얻은 1회용 토큰",
                example = "EXAMPLE-verification-token-not-real-0000000")
        String verificationToken
) {}
