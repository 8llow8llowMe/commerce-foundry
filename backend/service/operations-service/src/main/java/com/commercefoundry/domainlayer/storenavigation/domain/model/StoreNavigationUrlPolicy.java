package com.commercefoundry.domainlayer.storenavigation.domain.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 메뉴 링크 URL 규칙. 내부 경로는 같은 스토어의 상대 경로만, 외부 URL 은 설정한 허용 호스트의 https 만 받는다.
 * 퍼센트 인코딩·질의·fragment 는 열린 리다이렉트와 우회 표면을 줄이려고 받지 않는다.
 */
public final class StoreNavigationUrlPolicy {
    private static final String PATH_CHARS = "[A-Za-z0-9/._~-]*";
    private final Set<String> allowedExternalHosts;

    public StoreNavigationUrlPolicy(Set<String> hosts) {
        allowedExternalHosts = hosts.stream().map(host -> host.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        if (allowedExternalHosts.stream().anyMatch(host -> !externalDnsHost(host))) {
            throw new IllegalArgumentException("Invalid store navigation allowed external host configuration");
        }
    }

    public void validateInternal(String value) {
        if (value == null || value.length() > 512 || !value.startsWith("/") || value.startsWith("//")
            || !value.matches(PATH_CHARS) || hasDotSegment(value)) {
            invalid();
        }
    }

    public void validateExternal(String value) {
        if (value == null || value.isBlank() || value.length() > 2048
            || value.chars().anyMatch(c -> c <= 32 || c >= 127 || c == '\\' || c == '%')) {
            invalid();
        }
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.getRawAuthority().equalsIgnoreCase(host)) {
                invalid();
            }
            String path = uri.getRawPath();
            if (path == null || !path.matches(PATH_CHARS) || hasDotSegment(path)
                || !allowedExternalHosts.contains(host.toLowerCase(Locale.ROOT))) {
                invalid();
            }
        } catch (URISyntaxException exception) {
            invalid();
        }
    }

    private static boolean hasDotSegment(String path) {
        return Arrays.stream(path.split("/", -1)).anyMatch(segment -> segment.equals(".") || segment.equals(".."));
    }

    private static boolean externalDnsHost(String host) {
        if (host.length() > 253 || !host.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+")) {
            return false;
        }
        String[] labels = host.split("\\.");
        String tld = labels[labels.length - 1];
        if (!tld.matches("[a-z]{2,63}")
            || Set.of("localhost", "local", "localdomain", "internal", "lan", "home", "corp", "invalid", "test", "onion").contains(tld)) {
            return false;
        }
        return Arrays.stream(labels).allMatch(label -> label.length() <= 63 && !label.startsWith("-") && !label.endsWith("-"));
    }

    private static void invalid() {
        throw new StoreNavigationRuleException(StoreNavigationRuleException.Reason.URL);
    }
}
