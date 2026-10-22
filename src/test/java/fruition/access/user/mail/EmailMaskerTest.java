package fruition.access.user.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailMaskerTest {

    @Test
    void keepsTwoLeadingCharactersAndDomain() {
        assertThat(EmailMasker.mask("newuser@example.com")).isEqualTo("ne*****@example.com");
    }

    @Test
    void shortLocalPartKeepsOnlyFirstCharacterAndHidesLength() {
        assertThat(EmailMasker.mask("ab@example.com")).isEqualTo("a***@example.com");
        assertThat(EmailMasker.mask("a@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void malformedAddressIsFullyHidden() {
        assertThat(EmailMasker.mask("no-at-sign")).isEqualTo("***");
    }
}
