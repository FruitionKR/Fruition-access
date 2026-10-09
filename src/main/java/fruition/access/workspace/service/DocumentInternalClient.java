package fruition.access.workspace.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * document(문서 서비스) 내부 API client.
 * 워크스페이스 생성 직후 초기 노트 작성을 요청한다({@code POST /internal/workspaces/{id}/initial-note}).
 *
 * <p>초기 노트는 편의 기능이라 best-effort로 처리한다:
 * 호출 실패 시 warn 로그만 남기고 워크스페이스 생성은 성공시킨다.
 * 데이터 파기는 실패를 그대로 던져 호출한 쪽이 다시 시도하게 한다.
 */
@Component
public class DocumentInternalClient {

    private static final Logger log = LoggerFactory.getLogger(DocumentInternalClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    /** 파기는 저장소 객체를 지우느라 오래 걸릴 수 있다. 응답 전에 끊겨도 document가 끝까지 처리하고 다음 호출은 0건으로 끝난다. */
    private static final Duration PURGE_READ_TIMEOUT = Duration.ofSeconds(120);

    private final RestClient restClient;
    private final RestClient purgeClient;

    @Autowired
    public DocumentInternalClient(@Value("${app.internal.document-base-url}") String documentBaseUrl,
                                  @Value("${app.internal.callback-token}") String internalToken) {
        this(buildRestClient(documentBaseUrl, internalToken, READ_TIMEOUT),
                buildRestClient(documentBaseUrl, internalToken, PURGE_READ_TIMEOUT));
    }

    DocumentInternalClient(RestClient restClient) {
        this(restClient, restClient);
    }

    private DocumentInternalClient(RestClient restClient, RestClient purgeClient) {
        this.restClient = restClient;
        this.purgeClient = purgeClient;
    }

    private static RestClient buildRestClient(String documentBaseUrl, String internalToken, Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(documentBaseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    /** 초기 노트 생성 요청. 실패해도 예외를 전파하지 않는다(best-effort). */
    public void createInitialNote(String workspaceId, String userId) {
        try {
            restClient.post()
                    .uri("/internal/workspaces/{workspaceId}/initial-note", workspaceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("user_id", userId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("초기 노트 생성 요청 실패로 건너뜁니다. workspaceId={}", workspaceId, e);
        }
    }

    /** 워크스페이스의 문서·채팅·회의·파일을 document에서 지운다. 같은 요청을 다시 보내도 결과가 같다. */
    public void purgeWorkspace(String workspaceId) {
        purgeClient.post()
                .uri("/internal/purge/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("workspace_ids", List.of(workspaceId)))
                .retrieve()
                .toBodilessEntity();
    }
}
