package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.format}")
        @Schema(description = "비밀번호를 재설정할 계정 이메일", example = "user@example.com")
        String email,

        @JsonProperty("new_password")
        @NotBlank(message = "{validation.new_password.required}")
        @Size(min = 8, max = 72, message = "{validation.new_password.length}")
        @Schema(description = "새 비밀번호(8~72자). 성공하면 해당 사용자의 refresh token이 전부 폐기된다.",
                example = "password1234")
        String newPassword,

        @JsonProperty("verification_token")
        @NotBlank(message = "{validation.verification_token.required}")
        @Schema(description = "purpose=password_reset로 받은 1회용 토큰",
                example = "EXAMPLE-verification-token-not-real-0000000")
        String verificationToken
) {}
