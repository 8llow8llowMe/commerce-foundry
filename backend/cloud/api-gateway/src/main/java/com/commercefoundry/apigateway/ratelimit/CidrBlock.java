package com.commercefoundry.apigateway.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 신뢰 프록시 대역 하나 — {@code 10.0.0.0/8} · {@code 2001:db8::/32} · 단일 주소({@code 10.0.0.5}). 주소 계열(IPv4 · IPv6)이 다르면 맞지 않는다.
 * Spring Security 의 {@code IpAddressMatcher} 는 서블릿 오버로드가 있어 리액티브 게이트웨이에서 쓰지 않는다.
 *
 * <p>주소는 리터럴만 받는다 — 모양을 먼저 확인해 {@link InetAddress#getByName} 이 이름 조회를 하지 않게 한다.
 */
public final class CidrBlock {

    private static final Pattern IPV4 = Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    /**
     * 16진 · 콜론 · (IPv4 매핑 끝의) 점만, 콜론 둘 이상, 첫 글자는 16진이나 콜론. 이 모양이면 {@code getByName} 이 리터럴로 해석한다 —
     * 맨 앞이 점({@code .a:b:c})이면 JDK 가 리터럴 분기를 건너뛰고 OS 리졸버로 넘겨 이벤트 루프에서 이름 조회가 돈다.
     */
    private static final Pattern IPV6 = Pattern.compile("(?=(?:[^:]*:){2})[0-9A-Fa-f:][0-9A-Fa-f:.]{1,44}");
    /** 오타 · 실수 방지용 하한이다 — 이보다 넓은 대역은 받지 않지만, 공용 CDN 대역(예: /13)을 넣는 위험은 막지 못한다 ({@code security-guide.md} §7-3). */
    static final int MIN_IPV4_PREFIX = 8;
    static final int MIN_IPV6_PREFIX = 32;

    private final byte[] network;
    private final int prefixLength;

    private CidrBlock(byte[] network, int prefixLength) {
        this.network = network;
        this.prefixLength = prefixLength;
    }

    /** @throws IllegalArgumentException 주소 · CIDR 이 아니면 */
    public static CidrBlock parse(String value) {
        String[] parts = value.split("/", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("not a CIDR");
        }
        InetAddress address = literal(parts[0]).orElseThrow(() -> new IllegalArgumentException("not an IP literal"));
        byte[] bytes = address.getAddress();
        int maxPrefix = bytes.length * Byte.SIZE;
        int prefix = maxPrefix;
        if (parts.length == 2) {
            if (!parts[1].matches("\\d{1,3}")) {
                throw new IllegalArgumentException("not a prefix length");
            }
            prefix = Integer.parseInt(parts[1]);
            int minPrefix = bytes.length == 4 ? MIN_IPV4_PREFIX : MIN_IPV6_PREFIX;
            if (prefix > maxPrefix || prefix < minPrefix) {
                throw new IllegalArgumentException("prefix length out of range (IPv4 /" + MIN_IPV4_PREFIX + "~/32, IPv6 /" + MIN_IPV6_PREFIX + "~/128)");
            }
        }
        return new CidrBlock(bytes, prefix);
    }

    public boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / Byte.SIZE;
        for (int index = 0; index < fullBytes; index++) {
            if (candidate[index] != network[index]) {
                return false;
            }
        }
        int remainingBits = prefixLength % Byte.SIZE;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (Byte.SIZE - remainingBits)) & 0xFF;
        return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    /** IP 리터럴이면 주소, 아니면(호스트 이름 · 포트 · 대괄호 · 영역 id) 비어 있다. 이름 조회는 하지 않는다. */
    static Optional<InetAddress> literal(String value) {
        if (value == null || value.isEmpty() || !(IPV4.matcher(value).matches() || IPV6.matcher(value).matches())) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(value));
        } catch (UnknownHostException notLiteral) {
            return Optional.empty();
        }
    }
}
