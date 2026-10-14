package fruition.access.workspace.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class AiInternalClientTest {

    private MockRestServiceServer server;
    private AiInternalClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://ai.internal")
                .defaultHeader("X-Internal-Token", "test-internal-callback");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiInternalClient(builder.build());
    }

    @Test
    @DisplayName("워크스페이스 파기는 workspace_ids 배열과 내부 토큰으로 요청하고, 실패하면 예외를 던진다")
    void purgeWorkspace_sendsRequestAndPropagatesFailure() {
        server.expect(requestTo("http://ai.internal/internal/ai/purge/workspaces"))
                .andExpect(method(POST))
                .andExpect(header("X-Internal-Token", "test-internal-callback"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"workspace_ids\":[\"ws_aaa11111\"]}"))
                .andRespond(withStatus(HttpStatus.OK));
        server.expect(requestTo("http://ai.internal/internal/ai/purge/workspaces"))
                .andRespond(withServerError());

        client.purgeWorkspace("ws_aaa11111");
        assertThatThrownBy(() -> client.purgeWorkspace("ws_aaa11111")).isInstanceOf(RestClientException.class);

        server.verify();
    }

    @Test
    @DisplayName("회원 파기는 user_id 본문과 내부 토큰으로 요청한다")
    void purgeUser_sendsRequest() {
        server.expect(requestTo("http://ai.internal/internal/ai/purge/users"))
                .andExpect(method(POST))
                .andExpect(header("X-Internal-Token", "test-internal-callback"))
                .andExpect(content().json("{\"user_id\":\"user_1f9a74af\"}"))
                .andRespond(withStatus(HttpStatus.OK));

        client.purgeUser("user_1f9a74af");

        server.verify();
    }
}
