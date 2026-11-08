package fruition.access.mcp.oauth;

import org.apache.hc.client5.http.DnsResolver;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.DESTINATION_BLOCKED;

/** Resolves on the Apache connection path and returns only an all-public, immutable address set. */
final class PublicAddressDnsResolver implements DnsResolver {
    // IANA IPv4/IPv6 Special-Purpose registries, audited 2026-11-08; includes globally reachable entries.
    private static final Prefix[] IPV4_SPECIAL_USE = {
            prefix(8, 0), prefix(8, 10), prefix(10, 100, 64), prefix(8, 127),
            prefix(16, 169, 254), prefix(12, 172, 16), prefix(24, 192, 0),
            prefix(24, 192, 0, 2), prefix(24, 192, 31, 196), prefix(24, 192, 52, 193),
            prefix(24, 192, 88, 99), prefix(16, 192, 168), prefix(24, 192, 175, 48),
            prefix(15, 198, 18), prefix(24, 198, 51, 100), prefix(24, 203, 0, 113),
            prefix(4, 240)
    };
    private static final Prefix[] IPV6_SPECIAL_USE = {
            prefix(128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            prefix(128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            prefix(96, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff),
            prefix(96, 0, 0x64, 0xff, 0x9b), prefix(48, 0, 0x64, 0xff, 0x9b, 0, 1),
            prefix(64, 1, 0), prefix(64, 1, 0, 0, 0, 0, 0, 0, 1), prefix(23, 0x20, 1),
            prefix(32, 0x20, 1), prefix(128, 0x20, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            prefix(128, 0x20, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2),
            prefix(128, 0x20, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3),
            prefix(48, 0x20, 1, 0, 2), prefix(32, 0x20, 1, 0, 3), prefix(48, 0x20, 1, 0, 4, 1, 0x12),
            prefix(28, 0x20, 1, 0, 0x10), prefix(28, 0x20, 1, 0, 0x20), prefix(28, 0x20, 1, 0, 0x30),
            prefix(32, 0x20, 1, 0x0d, 0xb8), prefix(16, 0x20, 2), prefix(48, 0x26, 0x20, 0, 0x4f, 0x80),
            prefix(20, 0x3f, 0xff), prefix(16, 0x5f), prefix(7, 0xfc), prefix(10, 0xfe, 0x80)
    };

    @FunctionalInterface
    interface AddressLookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final AddressLookup lookup;

    PublicAddressDnsResolver() {
        this(InetAddress::getAllByName);
    }

    PublicAddressDnsResolver(AddressLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = lookup.resolve(host);
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !isPublic(address))) {
            throw new McpClientMetadataException(DESTINATION_BLOCKED, "CIMD destination is not public");
        }
        return addresses.clone();
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return address instanceof Inet4Address ? isPublicIpv4(bytes)
                : address instanceof Inet6Address && isPublicIpv6(bytes);
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        return !isSpecialUse(bytes, IPV4_SPECIAL_USE);
    }

    private static boolean isPublicIpv6(byte[] bytes) {
        if (isIpv4Mapped(bytes)) {
            return isPublicIpv4(Arrays.copyOfRange(bytes, 12, 16)) && !isSpecialUse(bytes, IPV6_SPECIAL_USE);
        }
        return !isSpecialUse(bytes, IPV6_SPECIAL_USE);
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) return false;
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    private static boolean isSpecialUse(byte[] address, Prefix[] prefixes) {
        for (Prefix prefix : prefixes) {
            if (prefix.matches(address)) return true;
        }
        return false;
    }

    private static Prefix prefix(int length, int... bytes) {
        byte[] network = new byte[(length + 7) / 8];
        for (int i = 0; i < bytes.length; i++) network[i] = (byte) bytes[i];
        return new Prefix(length, network);
    }

    private record Prefix(int length, byte[] network) {
        boolean matches(byte[] address) {
            int fullBytes = length / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) return false;
            }
            int remainder = length % 8;
            return remainder == 0 || (address[fullBytes] & (0xff << (8 - remainder)))
                    == (network[fullBytes] & (0xff << (8 - remainder)));
        }
    }
}
