package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record AccountDeletionRequest(
        @Size(max = 128, message = "password는 128자 이하여야 합니다.")
        @Schema(description = "비밀번호 계정의 현재 비밀번호. 소셜 전용 계정은 비우고 10분 안에 다시 로그인한 토큰으로 요청한다.")
        String password,

        @JsonProperty("mfa_code")
        @Size(max = 64, message = "mfa_code는 64자 이하여야 합니다.")
        @Schema(description = "MFA를 켠 계정의 인증 앱 코드 또는 복구 코드", example = "482917")
        String mfaCode
) {}
