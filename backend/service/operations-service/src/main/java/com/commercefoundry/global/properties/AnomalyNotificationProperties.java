package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** DLT 메타데이터 기반 알림. gate와 무관하게 잘못된 보존 설정은 기동에서 거절한다. */
@ConfigurationProperties("operations.notifications.anomaly")
public record AnomalyNotificationProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("1h") Duration suppressionWindow,
    @DefaultValue("8d") Duration recordRetention,
    @DefaultValue("1000") int maxRecipients,
    @DefaultValue("3") int retryAttempts,
    @DefaultValue("1s") Duration retryBackoff,
    @DefaultValue("10") int transactionTimeoutSeconds
) {
    public AnomalyNotificationProperties(boolean enabled, Duration suppressionWindow, Duration recordRetention,
        int maxRecipients, int retryAttempts, Duration retryBackoff) {
        this(enabled, suppressionWindow, recordRetention, maxRecipients, retryAttempts, retryBackoff, 10);
    }

    @ConstructorBinding
    public AnomalyNotificationProperties {
        if (suppressionWindow == null || suppressionWindow.isZero() || suppressionWindow.isNegative()
            || recordRetention == null || recordRetention.compareTo(Duration.ofDays(8)) < 0
            || maxRecipients < 1 || maxRecipients > 1000 || retryAttempts < 1 || retryAttempts > 3
            || retryBackoff == null || retryBackoff.isNegative() || retryBackoff.compareTo(Duration.ofSeconds(5)) > 0
            || transactionTimeoutSeconds < 1 || transactionTimeoutSeconds > 10) {
            throw new IllegalArgumentException("Invalid anomaly notification configuration");
        }
    }
}
