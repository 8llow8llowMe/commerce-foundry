package com.commercefoundry.domainlayer.look.domain.model;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 룩과 그 구성 상품 전체 — 쓰기 · 응답 조립의 단위다. 아이템은 룩 안의 순서({@code displayOrder} → 아이디)로 정렬해 든다.
 *
 * <p><b>불변식</b> — 아이템은 모두 이 룩의 것이고, 수는 1 ~ {@value LookLimits#MAX_ITEM_COUNT}, 같은 상품이 두 번 없다.
 * 요청 검증(개수)과 Processor(중복 → {@code LOOK_003})가 먼저 보므로 여기서 걸리면 호출 코드의 오류다. DB 의 {@code uk_look_item_look_id_product_id} 가 같은 규칙이다.
 */
public record LookContent(Look look, List<LookItem> items) {

    private static final Comparator<LookItem> ITEM_ORDER = Comparator.comparingInt(LookItem::displayOrder).thenComparingLong(LookItem::id);

    public LookContent {
        Objects.requireNonNull(look, "look");
        items = items.stream().sorted(ITEM_ORDER).toList();
        if (items.isEmpty() || items.size() > LookLimits.MAX_ITEM_COUNT) {
            throw new IllegalArgumentException("룩 구성 상품은 1~" + LookLimits.MAX_ITEM_COUNT + "개여야 합니다. lookId=" + look.id() + ", count=" + items.size());
        }
        for (LookItem item : items) {
            if (item.lookId() != look.id()) {
                throw new IllegalArgumentException("다른 룩의 아이템입니다. lookId=" + look.id() + ", itemLookId=" + item.lookId());
            }
        }
        if (hasDuplicateProduct(items.stream().map(LookItem::productId).toList())) {
            throw new IllegalArgumentException("한 룩에 같은 상품이 두 번 있습니다. lookId=" + look.id());
        }
    }

    /** 같은 상품 아이디가 두 번 이상 있는가. Processor 가 초안을 만들기 전에 불러 {@code LOOK_003} 으로 바꾼다. */
    public static boolean hasDuplicateProduct(List<Long> productIds) {
        Set<Long> seen = new HashSet<>();
        return !productIds.stream().allMatch(seen::add);
    }

    /** 구성 상품 아이디 — 룩 안의 순서. 불변식으로 중복이 없다. */
    public List<Long> productIds() {
        return items.stream().map(LookItem::productId).toList();
    }
}
