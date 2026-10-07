package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

public record OAuthLinkStartResponse(
        @JsonProperty("link_token")
        @Schema(description = "1회용 연동 토큰(60초). /oauth2/authorization/{provider}?mode=link&link_token=... 로 이동할 때 쓴다")
        String linkToken
) {}
