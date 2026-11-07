package com.commercefoundry.domainlayer.storefooter.domain.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class StoreFooterUrlPolicy {
    private final Set<String> allowedExternalHosts;

    public StoreFooterUrlPolicy(Set<String> hosts) {
        allowedExternalHosts = hosts.stream().map(host -> host.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        if (allowedExternalHosts.stream().anyMatch(host -> !externalDnsHost(host))) {
            throw new IllegalArgumentException("Invalid store footer allowed external host configuration");
        }
    }

    public void validate(String type, String value) {
        if (value == null || value.isBlank() || value.length() > 2048
            || value.chars().anyMatch(c -> c <= 32 || c >= 127 || c == '\\')) {
            invalid();
        }
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !externalDnsHost(host.toLowerCase(Locale.ROOT))
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.getRawAuthority().equalsIgnoreCase(host)) {
                invalid();
            }
            host = host.toLowerCase(Locale.ROOT);
            String path = asciiPath(uri.getRawPath());
            if (path == null || !path.matches("[A-Za-z0-9/._~!$&'()*+,;=:@-]*")
                || Arrays.stream(path.split("/", -1)).anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
                invalid();
            }
            boolean valid = switch (type) {
                case "INSTAGRAM" -> Set.of("instagram.com", "www.instagram.com").contains(host)
                    && path.matches("/[A-Za-z0-9_][A-Za-z0-9._]{0,29}/?");
                case "KAKAO_CHANNEL" -> "pf.kakao.com".equals(host) && path.matches("/_[A-Za-z0-9-]+(/chat)?");
                case "CUSTOM" -> allowedExternalHosts.contains(host);
                default -> false;
            };
            if (!valid) {
                invalid();
            }
        } catch (URISyntaxException exception) {
            invalid();
        }
    }

    private static String asciiPath(String rawPath) {
        if (rawPath == null) {
            invalid();
        }
        StringBuilder path = new StringBuilder();
        for (int index = 0; index < rawPath.length(); index++) {
            char current = rawPath.charAt(index);
            if (current == '%') {
                if (index + 2 >= rawPath.length()) {
                    invalid();
                }
                int high = Character.digit(rawPath.charAt(index + 1), 16);
                int low = Character.digit(rawPath.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    invalid();
                }
                current = (char) (high * 16 + low);
                // Only encoded ASCII unreserved characters are accepted; never delimiters or nested escapes.
                if (!(current >= 'A' && current <= 'Z') && !(current >= 'a' && current <= 'z')
                    && !(current >= '0' && current <= '9') && "-._~".indexOf(current) < 0) {
                    invalid();
                }
                index += 2;
            }
            path.append(current);
        }
        return path.toString();
    }

    private static boolean externalDnsHost(String host) {
        if (host.length() > 253 || !host.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+")) {
            return false;
        }
        String[] labels = host.split("\\.");
        if (!labels[labels.length - 1].matches("[a-z]{2,63}")) {
            return false;
        }
        if (Set.of("localhost", "local", "localdomain", "internal", "lan", "home", "corp", "invalid", "test", "onion")
            .contains(labels[labels.length - 1])) {
            return false;
        }
        return Arrays.stream(labels).allMatch(label -> label.length() <= 63 && !label.startsWith("-") && !label.endsWith("-"));
    }

    private static void invalid() {
        throw new StoreFooterRuleException(StoreFooterRuleException.Reason.URL);
    }
}
