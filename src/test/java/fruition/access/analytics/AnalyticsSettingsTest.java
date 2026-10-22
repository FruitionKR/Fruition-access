package fruition.access.analytics;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsSettingsTest {
    @Test void onlyPublicProjectTokensEnableRelayAndStringRepresentationRedacts() {
        for (String token : new String[]{"", "phx_testonly", "arbitrary", "phc_", "phc_contains space"}) {
            assertThat(new AnalyticsSettings(true, token, "https://us.i.posthog.com", "local", "test")
                    .relayEnabled()).isFalse();
        }
        var settings = new AnalyticsSettings(true, "phc_testonly", "https://us.i.posthog.com", "local", "test");
        assertThat(settings.relayEnabled()).isTrue();
        assertThat(settings.toString()).doesNotContain("phc_", "testonly");
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
