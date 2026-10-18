package fruition.shared.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AiModelCatalog {

    public static final String DEFAULT_PROVIDER = "openai";
    public static final String DEFAULT_MODEL = "gpt-6-luna";

    // 2026-10-06 실제 호출에서 JSON 응답을 확인한 텍스트 생성 모델이다(#45).
    // 2026-10-18 비용 정책으로 provider마다 가장 싼 모델 하나만 남겼다(그 밖의 모델은 resolve()가 400).
    // 표시 이름은 각 provider 공식 모델 문서의 표기를 따른다(2026-10-06 확인).
    // 화이트리스트라 폐기·JSON 모드 미지원·비텍스트 모델은 넣지 않는다.
    // 기준은 Fruition-document의 같은 목록이다. CI(scripts/check-ai-model-catalog.py)가 document dev와 다르면 실패하므로 document를 먼저 머지한다(#8).
    // 프론트는 저장된 선택이 없으면 첫 항목을 고른다. 그래서 provider마다 기존 기본 모델을 맨 앞에 둔다.
    private static final List<AiModel> SUPPORTED_MODELS = List.of(
            new AiModel("openai", "gpt-6-luna", "GPT-6 Luna"),
            new AiModel("gemini", "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"),
            new AiModel("claude", "claude-haiku-4-5-20251001", "Claude Haiku 4.5")
    );

    private final Set<String> enabledProviders;

    public AiModelCatalog(@Value("${app.ai.enabled-providers:openai,gemini,claude}") String enabledProviders) {
        this.enabledProviders = Arrays.stream(enabledProviders.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public List<AiModel> enabledModels() {
        return SUPPORTED_MODELS.stream()
                .filter(model -> enabledProviders.contains(model.provider()))
                .toList();
    }

    public AiModel resolve(String provider, String model) {
        String resolvedProvider = provider == null ? DEFAULT_PROVIDER : provider.trim().toLowerCase();
        String resolvedModel = model == null ? DEFAULT_MODEL : model.trim();
        if ((provider == null) != (model == null)) {
            throw new InvalidAiModelException("provider와 model은 함께 전달해야 합니다.");
        }
        return enabledModels().stream()
                .filter(candidate -> candidate.provider().equals(resolvedProvider)
                        && candidate.model().equals(resolvedModel))
                .findFirst()
                .orElseThrow(() -> new InvalidAiModelException("선택할 수 없는 AI 모델입니다."));
    }

    public record AiModel(String provider, String model, String displayName) {}
}
