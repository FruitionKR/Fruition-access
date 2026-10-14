package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record EmailAvailabilityResponse(
        @Schema(description = "이메일로 신규 가입할 수 있으면 true. 같은 이메일의 계정이 하나라도 있으면(이메일 가입이든 소셜 가입이든) false",
                example = "true")
        boolean available,

        @JsonProperty("oauth_providers")
        @Schema(description = "같은 이메일로 소셜 가입한 계정의 provider(이름순). 비어 있지 않으면 이메일 가입은 409 "
                + "EMAIL_REGISTERED_WITH_OAUTH로 거절되므로, 그 소셜 계정으로 로그인한 뒤 설정에서 연동하도록 안내한다.",
                example = "[\"google\"]")
        List<String> oauthProviders
) {}
