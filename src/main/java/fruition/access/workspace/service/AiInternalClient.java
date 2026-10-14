package fruition.access.workspace.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * ai-svc(pipeline-api) 내부 API client. document 파기가 끝난 뒤 AI 데이터(위키·스킬·에이전트 기록·S3 객체)를 지운다.
 * 실패를 그대로 던져 호출한 쪽이 전체 파기 순서를 다시 시도하게 한다.
 */
@Component
public class AiInternalClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private final RestClient restClient;

    @Autowired
    public AiInternalClient(@Value("${app.ai.internal-base-url}") String aiBaseUrl,
                            @Value("${app.internal.callback-token}") String internalToken,
                            @Value("${app.ai.purge-read-timeout:120s}") Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
        // ai가 S3 객체를 지우느라 오래 걸릴 수 있다. 응답 전에 끊겨도 다음 호출은 0건으로 끝난다.
        factory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(aiBaseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    AiInternalClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** 워크스페이스의 AI 데이터를 지운다. 같은 요청을 다시 보내도 결과가 같다. */
    public void purgeWorkspace(String workspaceId) {
        restClient.post()
                .uri("/internal/ai/purge/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("workspace_ids", List.of(workspaceId)))
                .retrieve()
                .toBodilessEntity();
    }

    /** 탈퇴 사용자가 공유 워크스페이스에 남긴 개인 AI 데이터를 지운다. 같은 요청을 다시 보내도 결과가 같다. */
    public void purgeUser(String userId) {
        restClient.post()
                .uri("/internal/ai/purge/users")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("user_id", userId))
                .retrieve()
                .toBodilessEntity();
    }
}
