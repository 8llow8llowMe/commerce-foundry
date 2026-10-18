package com.commercefoundry.apigateway.ratelimit;

import com.commercefoundry.apigateway.properties.GatewayClientIpProperties;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * 비로그인 요청의 클라이언트 IP 를 정한다 ({@code security-guide.md} §7-3). 이 값으로 버킷을 나누므로 <b>클라이언트가 바꿀 수 있는 값을 믿지 않는다.</b>
 *
 * <ol>
 *   <li>소켓의 원격 주소가 신뢰 프록시가 아니면 그 주소가 클라이언트다 — {@code X-Forwarded-For} 는 무시한다. 신뢰 프록시가 없는 기본값은 언제나 여기다.</li>
 *   <li>신뢰 프록시면 {@code X-Forwarded-For} 를 <b>오른쪽부터</b> 읽어, 신뢰 프록시를 건너뛴 첫 주소를 클라이언트로 본다.
 *       맨 왼쪽은 클라이언트가 마음대로 넣을 수 있는 값이라 쓰지 않는다 — 클라이언트가 {@code X-Forwarded-For: 1.2.3.4} 를 보내도
 *       LB 가 실제 주소를 뒤에 덧붙이므로 덧붙인 값이 쓰인다.</li>
 *   <li>헤더가 없거나, 모두 신뢰 프록시이거나, 주소가 아닌 값을 만나면 원격 주소(프록시)를 쓴다 — 그 프록시 뒤 요청이 한 버킷에 모이는 닫힌 쪽이다.</li>
 * </ol>
 *
 * <p>{@code Forwarded}(RFC 7239) 헤더는 보지 않는다. 프레임워크가 전달 헤더로 원격 주소를 바꾸지 않도록 {@code server.forward-headers-strategy: none} 이다.
 * 주소는 리터럴만 받고 호스트 이름은 DNS 를 찾지 않고 버린다({@link CidrBlock#literal}).
 */
@Component
public class ClientIpResolver {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final List<CidrBlock> trustedProxies;

    public ClientIpResolver(GatewayClientIpProperties properties) {
        this.trustedProxies = properties.trustedProxies().stream().map(CidrBlock::parse).toList();
    }

    /** @return 클라이언트 IP 문자열(정규화된 표기). 원격 주소조차 모르면 비어 있다 */
    public Optional<String> resolve(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return Optional.empty();
        }
        InetAddress remoteAddress = remote.getAddress();
        if (!isTrusted(remoteAddress)) {
            return Optional.of(remoteAddress.getHostAddress());
        }
        List<String> hops = forwardedHops(request);
        for (int index = hops.size() - 1; index >= 0; index--) {
            Optional<InetAddress> hop = CidrBlock.literal(hops.get(index));
            if (hop.isEmpty()) {
                return Optional.of(remoteAddress.getHostAddress());
            }
            if (!isTrusted(hop.get())) {
                return Optional.of(hop.get().getHostAddress());
            }
        }
        return Optional.of(remoteAddress.getHostAddress());
    }

    /**
     * 버킷을 나누는 단위로 바꾼다 — IPv4 는 주소 하나, <b>IPv6 는 /64</b>. 한 가입자가 보통 /64 를 받아 그 안에서 주소를 마음대로 바꿀 수 있으므로,
     * 주소 하나 단위로 세면 주소를 바꿔 가며 끝없이 새 버킷을 얻는다. {@link #resolve} 결과를 넘긴다.
     */
    public static String bucketOf(String clientIp) {
        Optional<InetAddress> address = CidrBlock.literal(clientIp);
        if (address.isEmpty() || address.get().getAddress().length != IPV6_BYTES) {
            return clientIp;
        }
        byte[] network = address.get().getAddress();
        Arrays.fill(network, IPV6_BYTES / 2, IPV6_BYTES, (byte) 0);
        try {
            return InetAddress.getByAddress(network).getHostAddress() + "/64";
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final int IPV6_BYTES = 16;

    private boolean isTrusted(InetAddress address) {
        return trustedProxies.stream().anyMatch(block -> block.contains(address));
    }

    /** 여러 줄로 와도 순서대로 이어 붙인다 — 프록시마다 한 줄씩 덧붙일 수 있다. */
    private static List<String> forwardedHops(ServerHttpRequest request) {
        List<String> hops = new ArrayList<>();
        for (String line : request.getHeaders().getOrEmpty(X_FORWARDED_FOR)) {
            for (String hop : line.split(",", -1)) {
                hops.add(hop.strip());
            }
        }
        return hops;
    }
}
