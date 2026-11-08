package fruition.shared.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 한국어·영어 번들이 같은 키를 갖고, 예외 처리기가 쓰는 오류 코드마다 문구가 있는지 확인한다. */
class MessageBundleConsistencyTest {

    @ParameterizedTest
    @ValueSource(strings = {"messages", "mail"})
    void koreanAndEnglishBundlesHaveSameKeys(String basename) throws IOException {
        Set<String> korean = load("i18n/" + basename + ".properties").stringPropertyNames();
        Set<String> english = load("i18n/" + basename + "_en.properties").stringPropertyNames();

        assertThat(new TreeSet<>(english)).isEqualTo(new TreeSet<>(korean));
    }

    @Test
    void everyHandledErrorCodeHasMessage() throws IOException {
        Set<String> keys = load("i18n/messages.properties").stringPropertyNames();
        Pattern errorCall = Pattern.compile("error\\(\"([A-Z_]+)\"");
        Set<String> missing = new TreeSet<>();
        for (String source : new String[]{
                "src/main/java/fruition/access/AccessExceptionHandler.java",
                "src/main/java/fruition/shared/util/BaseExceptionHandler.java"}) {
            Matcher matcher = errorCall.matcher(Files.readString(Path.of(source)));
            while (matcher.find()) {
                String code = matcher.group(1);
                boolean present = keys.contains("error." + code)
                        || keys.stream().anyMatch(key -> key.startsWith("error." + code + "."));
                if (!present) {
                    missing.add(code);
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    private static Properties load(String path) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = MessageBundleConsistencyTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
