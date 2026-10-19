package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailChangeRequest(
        @JsonProperty("new_email")
        @NotBlank(message = "new_email은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Size(max = 255, message = "new_email은 255자 이하여야 합니다.")
        @Schema(description = "바꿀 새 이메일. 이 주소로 받은 인증번호의 토큰이어야 한다.",
                example = "new@example.com")
        String newEmail,

        @JsonProperty("verification_token")
        @NotBlank(message = "verification_token은 필수입니다.")
        @Schema(description = "purpose=email_change로 받은 1회용 토큰",
                example = "EXAMPLE-verification-token-not-real-0000000")
        String verificationToken,

        @JsonProperty("current_password")
        @Size(max = 128, message = "current_password는 128자 이하여야 합니다.")
        @Schema(description = "비밀번호 계정의 현재 비밀번호(필수). 소셜 전용 계정은 비우고 10분 안에 다시 로그인한 토큰으로 요청한다.",
                example = "password1234")
        String currentPassword,

        @JsonProperty("mfa_code")
        @Size(max = 64, message = "mfa_code는 64자 이하여야 합니다.")
        @Schema(description = "MFA를 켠 계정의 인증 앱 코드 또는 복구 코드", example = "482917")
        String mfaCode
) {
    public EmailChangeRequest(String newEmail, String verificationToken) {
        this(newEmail, verificationToken, null, null);
    }
}
