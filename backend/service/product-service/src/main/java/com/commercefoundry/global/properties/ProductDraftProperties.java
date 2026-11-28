package com.commercefoundry.global.properties;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * AI 상품 초안 설정 (#359). 기본값은 {@code application.yml} 에 둔다 — 값이 빠지거나 0 · 음수면 기동하지 않는다.
 *
 * @param ttl 초안 보관 기간 — 만료 시각 = 만든 시각 + ttl. 지난 초안은 적용 · 거절할 수 없다(읽을 때 판정하고 정리 작업은 두지 않는다)
 * @param generationEnabled AI 생성(어드민 생성 시작 · 재생성 · 결과 반영과 operations 전용 internal generation-input · generation-images)을 연다. 기본은 닫힘(false)이다 —
 *                          상품 적용(#359 C2)과 운영 검증 뒤에만 켠다. 닫혀 있으면 internal 이 {@code PRODUCT_DRAFT_040}, 어드민이 {@code 050}(400)이고,
 *                          이미 만든 초안의 바이트도 내보내지 않는다(중단 스위치). 초안 생성의 키 · ETag 고정은 이 값과 상관없이 늘 한다
 * @param generationSlotTtl 매장 전체 동시 생성 상한(3건, #359 D13)에서 한 초안이 자리를 차지하는 시간 — 회차를 시작한 뒤 이 시간이 지난 생성 대기 · 생성 중 초안은
 *                          세지 않는다. 결과 반영이 운영자의 refresh 로만 일어나 방치된 초안이 보관 기간 내내 자리를 막지 않게 한다. 실제 AI 동시 호출 상한은
 *                          operations 핸들러(동시 1)가 따로 지킨다. 값이 없으면 30분이다
 */
@ConfigurationProperties(prefix = "product-draft")
public record ProductDraftProperties(Duration ttl, boolean generationEnabled, Duration generationSlotTtl) {

    /** {@code generation-slot-ttl} 기본값. */
    public static final Duration DEFAULT_GENERATION_SLOT_TTL = Duration.ofMinutes(30);

    @ConstructorBinding
    public ProductDraftProperties {
        Objects.requireNonNull(ttl, "product-draft.ttl 설정이 없습니다.");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("product-draft.ttl 는 0 보다 커야 합니다: " + ttl);
        }
        generationSlotTtl = generationSlotTtl == null ? DEFAULT_GENERATION_SLOT_TTL : generationSlotTtl;
        if (generationSlotTtl.isZero() || generationSlotTtl.isNegative()) {
            throw new IllegalArgumentException("product-draft.generation-slot-ttl 는 0 보다 커야 합니다: " + generationSlotTtl);
        }
    }

    /** 자리 시간을 기본값(30분)으로 둔다 — 시험 · 수동 구성용. */
    public ProductDraftProperties(Duration ttl, boolean generationEnabled) {
        this(ttl, generationEnabled, DEFAULT_GENERATION_SLOT_TTL);
    }
}
