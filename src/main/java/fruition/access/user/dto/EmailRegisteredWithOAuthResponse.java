package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 이메일 가입 거절(409 EMAIL_REGISTERED_WITH_OAUTH). 공통 오류 본문에 로그인·연동을 안내할 provider 목록을 더한다. */
public record EmailRegisteredWithOAuthResponse(
        ErrorResponse.ErrorDetail error,
        @JsonProperty("oauth_providers")
        @Schema(description = "같은 이메일로 소셜 가입한 계정의 provider(이름순)", example = "[\"google\"]")
        List<String> oauthProviders
) {}
