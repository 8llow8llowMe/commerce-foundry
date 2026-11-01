package com.commercefoundry.global.properties;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 상품 초안 설정 (#359). 기본값은 {@code application.yml} 에 둔다 — 값이 빠지거나 0 · 음수면 기동하지 않는다.
 *
 * @param ttl 초안 보관 기간 — 만료 시각 = 만든 시각 + ttl. 지난 초안은 적용 · 거절할 수 없다(읽을 때 판정하고 정리 작업은 두지 않는다)
 */
@ConfigurationProperties(prefix = "product-draft")
public record ProductDraftProperties(Duration ttl) {

    public ProductDraftProperties {
        Objects.requireNonNull(ttl, "product-draft.ttl 설정이 없습니다.");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("product-draft.ttl 는 0 보다 커야 합니다: " + ttl);
        }
    }
}
