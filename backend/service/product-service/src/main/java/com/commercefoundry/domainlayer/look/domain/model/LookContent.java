package com.commercefoundry.domainlayer.look.domain.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * 룩마다 아이템을 붙인다 — 룩 순서를 지킨다. <b>아이템이 없는 룩은 결과에서 뺀다</b>: 잠그지 않는 읽기에서 룩 행을 읽은 뒤 아이템을 읽기 전에
     * 다른 어드민의 삭제가 커밋된 경우다(READ COMMITTED 의 문장별 스냅숏). 지워진 룩은 목록에 없는 것이 맞고, 불변식(1개 이상)으로 500 이 나지 않는다.
     * 어드민 목록 · 고객 목록이 같은 규칙을 쓴다.
     *
     * @param items 룩들의 아이템 (순서 무관 — 룩별로 다시 정렬한다)
     */
    public static List<LookContent> assemble(List<Look> looks, List<LookItem> items) {
        Map<Long, List<LookItem>> itemsByLook = new HashMap<>();
        items.forEach(item -> itemsByLook.computeIfAbsent(item.lookId(), lookId -> new ArrayList<>()).add(item));
        return looks.stream()
            .filter(look -> itemsByLook.containsKey(look.id()))
            .map(look -> new LookContent(look, itemsByLook.get(look.id())))
            .toList();
    }

    /** 룩들의 구성 상품 아이디 — 모든 룩을 합쳐 중복 없이, 룩 · 구성 순서. 상품 조회를 한 번에 하게 한다(어드민 · 고객 공용). */
    public static Set<Long> productIdsOf(Collection<LookContent> contents) {
        Set<Long> productIds = new LinkedHashSet<>();
        contents.forEach(content -> productIds.addAll(content.productIds()));
        return productIds;
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
