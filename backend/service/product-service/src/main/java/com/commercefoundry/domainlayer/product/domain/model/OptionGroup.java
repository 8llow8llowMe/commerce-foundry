package com.commercefoundry.domainlayer.product.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 상품 옵션 그룹 (색상 · 사이즈). 상품당 최대 {@link SkuOptionKey#MAX_GROUPS} 개다.
 *
 * @param retiredAt 은퇴 시각. null 이면 사용 중이다. 은퇴한 그룹은 고객 상세에서 빠지지만 행은 남는다 — 서비스 간 SKU 조회는 옵션명을 계속 읽는다
 */
public record OptionGroup(long id, long productId, String name, int displayOrder, Instant retiredAt) {

    public OptionGroup {
        Objects.requireNonNull(name, "name");
    }

    /** 은퇴한 그룹인가. */
    public boolean isRetired() {
        return retiredAt != null;
    }
}
