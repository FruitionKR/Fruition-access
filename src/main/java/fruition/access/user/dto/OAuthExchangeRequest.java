package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record OAuthExchangeRequest(
        @NotBlank(message = "code는 필수입니다.")
        @Schema(description = "OAuth 로그인 성공 후 redirect로 받은 1회용 교환 코드(TTL 60초)")
        String code,

        @JsonProperty("code_verifier")
        @Schema(description = "데스크톱 로그인에서 시작할 때 보낸 code_challenge의 원문(PKCE, 43~128자 [A-Za-z0-9-._~]). 웹 로그인은 보내지 않는다.")
        String codeVerifier
) {}
