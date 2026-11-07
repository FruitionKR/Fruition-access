package fruition.access.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record EmailVerificationRequest(
        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.format}")
        @Schema(description = "인증번호를 받을 이메일", example = "user@example.com")
        String email,

        @NotBlank(message = "{validation.purpose.required}")
        @Pattern(regexp = "signup|password_reset|email_change",
                message = "{validation.purpose.allowed}")
        @Schema(description = "인증 목적. signup은 이미 가입된 이메일이면 409로 거절된다."
                        + " email_change는 바꾸려는 새 이메일로 발송하며, 중복 확인은 확정 시점에 한다.",
                allowableValues = {"signup", "password_reset", "email_change"}, example = "signup")
        String purpose
) {}
