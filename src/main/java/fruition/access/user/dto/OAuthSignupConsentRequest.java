package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 소셜 신규 가입 확정. OAuth 콜백이 넘긴 signup_token과 동의 항목을 함께 보낸다. */
public record OAuthSignupConsentRequest(
        @JsonProperty("signup_token")
        @NotBlank(message = "signup_token은 필수입니다.")
        @Schema(description = "OAuth 콜백 주소에 ?signup_token=으로 붙어 온 1회용 토큰")
        String signupToken,

        @JsonProperty("age_confirmed")
        @Schema(description = "만 18세 이상 확인. true여야 한다.", example = "true")
        Boolean ageConfirmed,

        @JsonProperty("terms_version")
        @Size(max = 32)
        @Schema(description = "화면에 보여 준 이용약관 버전. 서버의 현재 버전과 같아야 한다.", example = "2026-10-01")
        String termsVersion,

        @JsonProperty("marketing_opt_in")
        @NotNull(message = "marketing_opt_in은 필수입니다.")
        @Schema(description = "마케팅 정보 수신 동의(선택 항목)", example = "false")
        Boolean marketingOptIn,

        @JsonProperty("code_verifier")
        @Schema(description = "데스크톱 로그인에서 시작할 때 보낸 code_challenge의 원문(PKCE, 43~128자 [A-Za-z0-9-._~]). 웹 로그인은 보내지 않는다.")
        String codeVerifier
) {}
