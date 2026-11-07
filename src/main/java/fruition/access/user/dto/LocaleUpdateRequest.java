package fruition.access.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record LocaleUpdateRequest(
        @NotNull(message = "{validation.locale.required}")
        @Pattern(regexp = "ko|en", message = "{validation.locale.allowed}")
        @Schema(description = "저장할 화면 언어", example = "en", allowableValues = {"ko", "en"})
        String locale
) {}
