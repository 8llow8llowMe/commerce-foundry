package com.commercefoundry.global.properties;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SMTP 자격은 Vault에서 주입한다. 기능이 켜졌을 때 필수 설정과 HTTPS 주소를 검증한다. */
@ConfigurationProperties("auth.admin-password-reset")
public record AdminPasswordResetProperties(boolean enabled, String resetUrl, String smtpHost, int smtpPort,
    String smtpUsername, String smtpPassword, String sender, Duration tokenTtl) {

    public AdminPasswordResetProperties {
        tokenTtl = tokenTtl == null ? Duration.ofMinutes(15) : tokenTtl;
        if (tokenTtl.compareTo(Duration.ofMinutes(1)) < 0 || tokenTtl.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("비밀번호 재설정 설정을 확인해 주세요");
        }
        if (enabled) {
            if (blank(resetUrl) || blank(smtpHost) || smtpPort < 1 || smtpPort > 65535 || blank(smtpUsername)
                || blank(smtpPassword) || blank(sender) || !sender.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                throw new IllegalArgumentException("비밀번호 재설정 설정을 확인해 주세요");
            }
            URI uri;
            try { uri = URI.create(resetUrl); } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("비밀번호 재설정 설정을 확인해 주세요");
            }
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("비밀번호 재설정 설정을 확인해 주세요");
            }
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank() || value.contains("${"); }

    @Override
    public String toString() { return "AdminPasswordResetProperties[enabled=" + enabled + "]"; }
}
