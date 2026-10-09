package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 약관이 바뀐 뒤 기존 회원이 다시 동의할 때 보낸다. */
public record ConsentRequest(
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
        Boolean marketingOptIn
) {}
