package com.commercefoundry.global.properties;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 상품 초안 설정 (#359). 기본값은 {@code application.yml} 에 둔다 — 값이 빠지거나 0 · 음수면 기동하지 않는다.
 *
 * @param ttl 초안 보관 기간 — 만료 시각 = 만든 시각 + ttl. 지난 초안은 적용 · 거절할 수 없다(읽을 때 판정하고 정리 작업은 두지 않는다)
 * @param generationEnabled operations 생성 작업 전용 internal(generation-input · generation-images)을 연다. 기본은 닫힘(false)이다 —
 *                          operations 생성 핸들러(#359 PR-2b)와 연결(PR-3) · 운영 검증 뒤에만 켠다. 닫혀 있으면 두 API 가 {@code PRODUCT_DRAFT_040}(400)이고,
 *                          이미 만든 초안의 바이트도 내보내지 않는다(중단 스위치). 초안 생성의 키 · ETag 고정은 이 값과 상관없이 늘 한다
 */
@ConfigurationProperties(prefix = "product-draft")
public record ProductDraftProperties(Duration ttl, boolean generationEnabled) {

    public ProductDraftProperties {
        Objects.requireNonNull(ttl, "product-draft.ttl 설정이 없습니다.");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("product-draft.ttl 는 0 보다 커야 합니다: " + ttl);
        }
    }
}
