package fruition.access.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fruition.TestcontainersConfiguration;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"app.analytics.enabled=true", "app.analytics.project-token="})
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AnalyticsOutboxIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired PlatformTransactionManager manager;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired UserRepository users;
    @Autowired ObjectMapper mapper;
    @Autowired AnalyticsRelay disabledRelay;
    @Autowired fruition.access.workspace.service.WorkspaceService workspaces;
    @Autowired fruition.access.workspace.service.WorkspaceInvitationService invitations;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    fruition.access.user.mail.WorkspaceInvitationSender invitationSender;
    final Instant occurred = Instant.parse("2026-10-22T01:02:03.123Z");

    @BeforeEach void clear() { jdbc.update("DELETE FROM analytics_outbox"); }

    AnalyticsEvent signup(String id) {
        return new AnalyticsEvent("user_signed_up", id, id, null, occurred, Map.of("method", "email"));
    }
    void record(String id) { tx.executeWithoutResult(status -> publisher.publishEvent(signup(id))); }
    int count() { return jdbc.queryForObject("SELECT count(*) FROM analytics_outbox", Integer.class); }
    Map<String, Object> row() { return jdbc.queryForMap("SELECT * FROM analytics_outbox"); }
    AnalyticsRelay relay(String host) {
        return new AnalyticsRelay(jdbc, mapper, new AnalyticsSettings(true, "phc_testonly", host,
                "local", "test"), manager);
    }

    @Test void committedJpaProductAndOutboxRollbackTogether() {
        String id = "user_" + UUID.randomUUID().toString().replace("-", "");
        tx.executeWithoutResult(status -> {
            users.saveAndFlush(new User(id, id + "@example.test", "local", "Test", null));
            publisher.publishEvent(signup(id));
            assertThat(count()).isOne();
            status.setRollbackOnly();
        });
        assertThat(users.existsById(id)).isFalse();
        assertThat(count()).isZero();
    }

    @Test void sqlFailureRollsBackSavepointButCommitsJpaProduct() {
        String id = "user_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("ALTER TABLE analytics_outbox ADD CONSTRAINT reject_test CHECK (event <> 'user_signed_up')");
        try {
            tx.executeWithoutResult(status -> {
                users.saveAndFlush(new User(id, id + "@example.test", "local", "Before", null));
                publisher.publishEvent(signup(id));
                // A SQL statement after the failed insert proves PostgreSQL is no longer aborted.
                jdbc.update("UPDATE users SET display_name = 'After' WHERE id = ?", id);
            });
            assertThat(jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, id)).isEqualTo("After");
            assertThat(count()).isZero();
        } finally { jdbc.execute("ALTER TABLE analytics_outbox DROP CONSTRAINT reject_test"); }
    }

    @Test void duplicateRetainsOriginalActorTimeAndProperties() throws Exception {
        record("user_one");
        tx.executeWithoutResult(status -> publisher.publishEvent(new AnalyticsEvent("user_signed_up", "user_one",
                "user_two", null, Instant.now(), Map.of("method", "google"))));
        assertThat(count()).isOne();
        assertThat(row().get("distinct_id")).isEqualTo("user_one");
        assertThat(((Timestamp) row().get("occurred_at")).toInstant()).isEqualTo(occurred);
        JsonNode properties = mapper.readTree(row().get("properties").toString());
        assertThat(properties.get("method").asText()).isEqualTo("email");
        assertThat(properties.get("event_id").asText()).isEqualTo(row().get("uuid").toString());
        assertThat(properties.get("schema_version").asInt()).isOne();
        assertThat(properties.get("event_source").asText()).isEqualTo("access_server");
        assertThat(properties.has("$groups")).isFalse();
        assertThat(properties.has("workspace_id")).isFalse();
    }

    @Test void allowlistRejectsUnknownPropertiesEnumsAndEventsWithoutFailingProduct() {
        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new AnalyticsEvent("user_signed_up", "user_one", "user_one", null, occurred,
                    Map.of("method", "email", "email", "private@example.test")));
            publisher.publishEvent(new AnalyticsEvent("user_signed_up", "user_two", "user_two", null, occurred,
                    Map.of("method", "password")));
            publisher.publishEvent(new AnalyticsEvent("unknown", "user_three", "user_three", null, occurred, Map.of()));
            publisher.publishEvent(new AnalyticsEvent("member_joined", "inv_one", "user_one", "ws_one", occurred,
                    Map.of("invitation_id", "inv_one", "role", "admin")));
        });
        assertThat(count()).isZero();
        assertThat(disabledRelay.isRunning()).isFalse();
    }

    @Test void disabledSwitchPreventsRecordingAndRelay() {
        AnalyticsSettings settings = new AnalyticsSettings(false, "phc_testonly", "http://127.0.0.1:1", "local", "test");
        AnalyticsRecorder recorder = new AnalyticsRecorder(jdbc.getDataSource(), mapper, settings);
        tx.executeWithoutResult(status -> recorder.record(signup("user_disabled")));
        AnalyticsRelay relay = new AnalyticsRelay(jdbc, mapper, settings, manager);
        relay.start();
        relay.runOnce(Instant.now());
        assertThat(relay.isRunning()).isFalse();
        assertThat(count()).isZero();
    }

    @Test void httpFailureRetryPreservesEntireEnvelopeAndConcurrentDispatchSkipsLocked() throws Exception {
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        AtomicInteger response = new AtomicInteger(503);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/batch/", exchange -> {
            received.add(mapper.readTree(exchange.getRequestBody()));
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(response.get(), -1);
            exchange.close();
        });
        server.start();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            record("user_one");
            String host = "http://127.0.0.1:" + server.getAddress().getPort();
            AnalyticsRelay first = relay(host);
            Instant now = Instant.now().plusSeconds(1);
            var dispatch = pool.submit(() -> first.runOnce(now));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            // A second independent transaction cannot pick the first relay's locked row.
            relay(host).runOnce(now);
            assertThat(received).hasSize(1);
            release.countDown();
            dispatch.get(5, TimeUnit.SECONDS);
            assertThat(row().get("attempts")).isEqualTo(1);
            assertThat(row().get("sent_at")).isNull();
            response.set(200);
            first.runOnce(now.plusSeconds(61));
            assertThat(received).hasSize(2);
            assertThat(received.get(1)).isEqualTo(received.get(0));
            assertThat(received.get(0).at("/batch/0/timestamp").asText()).isEqualTo(occurred.toString());
            assertThat(row().get("sent_at")).isNotNull();
            first.runOnce(now.plusSeconds(120));
            assertThat(received).hasSize(2);
            first.start();
            assertThat(first.isRunning()).isTrue();
            first.stop();
            assertThat(first.isRunning()).isFalse();
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void connectionFailureAndDeadThresholdKeepCommittedProduct() {
        record("user_one");
        jdbc.update("UPDATE analytics_outbox SET attempts = 23");
        // Bind then close an ephemeral listener: no external network destination is used.
        int port;
        try (var socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
        relay("http://127.0.0.1:" + port).runOnce(Instant.now().plusSeconds(1));
        assertThat(row().get("attempts")).isEqualTo(24);
        assertThat(row().get("dead")).isEqualTo(true);
        assertThat(row().get("last_error_code")).isEqualTo("transport_error");
    }

    @Test void timeoutDoesNotAffectCommittedProductAndRetryIsDurable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/batch/", exchange -> {
            try { release.await(8, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            String id = "user_" + UUID.randomUUID().toString().replace("-", "");
            tx.executeWithoutResult(status -> {
                users.saveAndFlush(new User(id, id + "@example.test", "local", "Test", null));
                publisher.publishEvent(signup(id));
            });
            relay("http://127.0.0.1:" + server.getAddress().getPort()).runOnce(Instant.now().plusSeconds(1));
            assertThat(users.existsById(id)).isTrue();
            assertThat(row().get("attempts")).isEqualTo(1);
            assertThat(row().get("last_error_code")).isEqualTo("timeout");
            assertThat(((Timestamp) row().get("occurred_at")).toInstant()).isEqualTo(occurred);
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void invitationReissueAndConcurrentJoinProduceOneEventEachWithOriginalActors() throws Exception {
        String owner = "user_" + UUID.randomUUID().toString().replace("-", "");
        String invitee = "user_" + UUID.randomUUID().toString().replace("-", "");
        tx.executeWithoutResult(status -> {
            users.saveAndFlush(new User(owner, owner + "@example.test", "local", "Private Owner", null));
            users.saveAndFlush(new User(invitee, invitee + "@example.test", "local", "Private Invitee", null));
        });
        String workspace = workspaces.createDefault(owner, "Private Workspace").getId();
        var request = new fruition.access.workspace.dto.WorkspaceInvitationCreateRequest(invitee + "@example.test",
                fruition.access.workspace.domain.WorkspaceRole.MEMBER);
        var created = invitations.invite(owner, workspace, request);
        JsonNode original = mapper.readTree(row().get("properties").toString());
        assertThat(row().get("distinct_id")).isEqualTo(owner);
        assertThat(original.at("/$groups/workspace").asText()).isEqualTo(workspace);
        assertThat(original.get("workspace_id").asText()).isEqualTo(workspace);
        assertThat(original.get("role").asText()).isEqualTo("member");
        assertThat(invitations.invite(owner, workspace, request).invitationId()).isEqualTo(created.invitationId());
        assertThat(count()).isOne();
        var url = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(invitationSender, org.mockito.Mockito.times(2)).send(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), url.capture());
        String link = url.getValue();
        String token = link.substring(link.lastIndexOf('/') + 1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> invitations.accept(invitee, token));
            var second = pool.submit(() -> invitations.accept(invitee, token));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        invitations.accept(invitee, token);
        assertThat(count()).isEqualTo(2);
        var joined = jdbc.queryForMap("SELECT * FROM analytics_outbox WHERE event = 'member_joined'");
        assertThat(joined.get("distinct_id")).isEqualTo(invitee);
        JsonNode properties = mapper.readTree(joined.get("properties").toString());
        assertThat(properties.at("/$groups/workspace").asText()).isEqualTo(workspace);
        assertThat(properties.get("workspace_id").asText()).isEqualTo(workspace);
        assertThat(properties.get("invitation_id").asText()).isEqualTo(created.invitationId());
        assertThat(properties.toString()).doesNotContain("Private", "@example", token);
    }

    @Test void rejectedInvitationAcceptRecordsOneFailureWithoutProductChanges() throws Exception {
        String owner = "user_" + UUID.randomUUID().toString().replace("-", "");
        String invitee = "user_" + UUID.randomUUID().toString().replace("-", "");
        String stranger = "user_" + UUID.randomUUID().toString().replace("-", "");
        tx.executeWithoutResult(status -> {
            users.saveAndFlush(new User(owner, owner + "@example.test", "local", "Private Owner", null));
            users.saveAndFlush(new User(invitee, invitee + "@example.test", "local", "Private Invitee", null));
            users.saveAndFlush(new User(stranger, stranger + "@example.test", "local", "Private Stranger", null));
        });
        String workspace = workspaces.createDefault(owner, "Private Workspace").getId();
        var created = invitations.invite(owner, workspace, new fruition.access.workspace.dto.WorkspaceInvitationCreateRequest(
                invitee + "@example.test", fruition.access.workspace.domain.WorkspaceRole.MEMBER));
        var url = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(invitationSender).send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), url.capture());
        String token = url.getValue().substring(url.getValue().lastIndexOf('/') + 1);
        for (int i = 0; i < 2; i++)
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> invitations.accept(stranger, token))
                    .isInstanceOf(fruition.access.workspace.exception.InvitationEmailMismatchException.class);
        jdbc.update("UPDATE workspace_invitations SET expires_at = now() - interval '1 minute' WHERE id = ?", created.invitationId());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> invitations.accept(invitee, token))
                .isInstanceOf(fruition.access.workspace.exception.InvitationExpiredException.class);
        var failures = jdbc.queryForList("SELECT distinct_id, properties->>'reason' AS reason, properties::text AS properties FROM analytics_outbox WHERE event = 'member_join_failed'");
        assertThat(failures).extracting(row -> row.get("distinct_id") + ":" + row.get("reason"))
                .containsExactlyInAnyOrder(stranger + ":email_mismatch", invitee + ":expired");
        assertThat(failures.toString()).contains(created.invitationId()).doesNotContain("Private", "@example", token);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE workspace_id = ?", Integer.class, workspace)).isOne();
        assertThat(jdbc.queryForObject("SELECT accepted_at IS NULL FROM workspace_invitations WHERE id = ?", Boolean.class, created.invitationId())).isTrue();
    }
    @Test void uuidMatchesSharedPythonUuid5Vector() {
        assertThat(AnalyticsRecorder.eventId("user_signed_up", "user_one").toString())
                .isEqualTo("b3d5fb6e-d5c6-573b-a0c8-0427f63f34dc");
    }
}
