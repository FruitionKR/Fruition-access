package fruition.access.mcp.oauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.DESTINATION_BLOCKED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicAddressDnsResolverTest {
    @ParameterizedTest
    @MethodSource("specialUseAddresses")
    void blocksEveryRegisteredSpecialUseIpv4AndIpv6Prefix(String address) throws Exception {
        var resolver = new PublicAddressDnsResolver(ignored -> new InetAddress[]{InetAddress.getByName(address)});

        assertThatThrownBy(() -> resolver.resolve("metadata.example"))
                .isInstanceOfSatisfying(McpClientMetadataException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DESTINATION_BLOCKED));
    }

    @ParameterizedTest
    @MethodSource("publicBoundaryAddresses")
    void allowsPublicAddressesOutsideTheSpecialUsePrefixes(String address) throws Exception {
        var resolver = new PublicAddressDnsResolver(ignored -> new InetAddress[]{InetAddress.getByName(address)});

        assertThat(resolver.resolve("public.example")).hasSize(1);
    }

    @Test
    void blocksIpv4MappedAddressesAsSpecialUse() throws Exception {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = 8;
        mapped[13] = 8;
        mapped[14] = 8;
        mapped[15] = 8;
        InetAddress address = Inet6Address.getByAddress(null, mapped, -1);
        var resolver = new PublicAddressDnsResolver(ignored -> new InetAddress[]{address});

        assertThatThrownBy(() -> resolver.resolve("mapped.example"))
                .isInstanceOfSatisfying(McpClientMetadataException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DESTINATION_BLOCKED));
    }

    @Test
    void rejectsTheWholeMixedAnswerSet() throws Exception {
        var resolver = new PublicAddressDnsResolver(ignored -> new InetAddress[]{
                InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")});

        assertThatThrownBy(() -> resolver.resolve("mixed.example"))
                .isInstanceOf(McpClientMetadataException.class);
    }

    @Test
    void resolvesAgainForEveryConnectionAndRejectsAReboundPrivateAnswer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var resolver = new PublicAddressDnsResolver(ignored -> calls.getAndIncrement() == 0
                ? new InetAddress[]{InetAddress.getByName("2606:4700:4700::1111")}
                : new InetAddress[]{InetAddress.getByName("169.254.169.254")});

        assertThat(resolver.resolve("changing.example")).hasSize(1);
        assertThatThrownBy(() -> resolver.resolve("changing.example"))
                .isInstanceOf(McpClientMetadataException.class);
        assertThat(calls).hasValue(2);
    }

    private static Stream<String> specialUseAddresses() {
        return Stream.of(
                "0.0.0.0", "10.0.0.0", "100.64.0.0", "127.0.0.0", "169.254.0.0", "172.16.0.0",
                "192.0.0.0", "192.0.2.0", "192.31.196.0", "192.52.193.0", "192.88.99.0", "192.168.0.0",
                "192.175.48.0", "198.18.0.0", "198.51.100.0", "203.0.113.0", "240.0.0.0",
                "::", "::1", "64:ff9b::", "64:ff9b:1::", "100::", "100:0:0:1::",
                "2001::", "2001:1::", "2001:1::1", "2001:1::2", "2001:1::3", "2001:2::", "2001:3::",
                "2001:4:112::", "2001:10::", "2001:20::", "2001:30::", "2001:db8::", "2002::",
                "2620:4f:8000::", "3fff::", "5f00::", "fc00::", "fe80::");
    }

    private static Stream<String> publicBoundaryAddresses() {
        return Stream.of(
                "8.8.8.8", "100.63.255.255", "100.128.0.0", "192.31.195.255", "192.31.197.0",
                "198.17.255.255", "198.20.0.0", "203.0.112.255", "203.0.114.0", "223.255.255.255",
                "2001:4860:4860::8888", "2003::", "3ffe:ffff::", "4000::", "5eff:ffff::", "6000::");
    }
}
