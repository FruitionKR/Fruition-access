package fruition.access.analytics;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsSettingsTest {
    @Test void onlyPublicProjectTokensEnableRelayAndStringRepresentationRedacts() {
        for (String token : new String[]{"", "phx_testonly", "arbitrary", "phc_", "phc_contains space",
                "phc_test.only", "phc_test/only", "phc_test+only", "phc_test=only", "phc_test\n", "phc_한글",
                "phx_dummy_local-token"}) {
            assertThat(new AnalyticsSettings(true, token, "https://us.i.posthog.com", "local", "test")
                    .relayEnabled()).isFalse();
        }
        var settings = new AnalyticsSettings(true, "phc_testonly", "https://us.i.posthog.com", "local", "test");
        assertThat(settings.relayEnabled()).isTrue();
        assertThat(settings.toString()).doesNotContain("phc_", "testonly");
    }

    @Test void composeContainerizedMockDefaultsEnableRelayOnlyWhenConfigured() {
        // Fruition-flatform infra/compose.containerized.yml LOCAL_POSTHOG_HOST default.
        String composeHost = "http://host.docker.internal:18080";
        for (String token : new String[]{"phc_testonly", "phc_dummy_local", "phc_dummy-local", "phc_dummy_local-token"}) {
            assertThat(new AnalyticsSettings(true, token, composeHost, "local", "test").relayEnabled())
                    .as("public project token %s", token).isTrue();
        }
        assertThat(new AnalyticsSettings(false, "phc_dummy_local", composeHost, "local", "test").relayEnabled()).isFalse();
        assertThat(new AnalyticsSettings(true, "", composeHost, "local", "test").relayEnabled()).isFalse();
        assertThat(new AnalyticsSettings(true, "phx_testonly", composeHost, "local", "test").relayEnabled()).isFalse();
    }

    @Test void httpOnlyAcceptsExplicitLocalHosts() {
        for (String host : new String[]{"localhost", "127.0.0.1", "[::1]", "host.docker.internal"}) {
            assertThat(new AnalyticsSettings(true, "phc_testonly", "http://" + host + ":18080", "local", "test")
                    .relayEnabled()).as(host).isTrue();
        }
        for (String host : new String[]{"example.test", "192.168.1.1", "gateway.docker.internal",
                "host.docker.internal.example.test", "sub.host.docker.internal", "host.docker.internal."}) {
            assertThat(new AnalyticsSettings(true, "phc_testonly", "http://" + host + ":18080", "local", "test")
                    .relayEnabled()).as(host).isFalse();
        }
    }

    @Test void localGatewayStillRejectsCredentialsQueryAndFragment() {
        for (String host : new String[]{"http://user:password@host.docker.internal:18080",
                "http://host.docker.internal:18080?token=test", "http://host.docker.internal:18080#fragment",
                "http://host.docker.internal:18080?", "http://host.docker.internal:18080#",
                "http://host.docker.internal@evil.test:18080", "ftp://host.docker.internal:18080"}) {
            assertThat(new AnalyticsSettings(true, "phc_testonly", host, "local", "test").relayEnabled())
                    .as(host).isFalse();
        }
    }

    @Test void malformedAnalyticsConfigurationDisablesWithoutThrowing() {
        assertThat(new AnalyticsSettings("garbage", "phc_testonly", "https://us.i.posthog.com", "local", "test").enabled()).isFalse();
        assertThat(new AnalyticsSettings(true, "phc_testonly", "not a URI", "local", "test").enabled()).isFalse();
        assertThat(new AnalyticsSettings(true, "phc_testonly", "https://us.i.posthog.com", "invalid", "test").enabled()).isFalse();
        assertThat(new AnalyticsSettings(true, "phc_testonly", "https://us.i.posthog.com", "local", "bad version").enabled()).isFalse();
        assertThat(new AnalyticsSettings(true, "phc_testonly", "http://example.test", "local", "test").enabled()).isFalse();
        assertThat(new AnalyticsSettings(true, "phc_testonly", "http://127.0.0.1:8080", "local", "test").enabled()).isTrue();
    }
}
