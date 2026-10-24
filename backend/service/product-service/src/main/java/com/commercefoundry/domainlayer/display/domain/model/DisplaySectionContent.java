package com.commercefoundry.domainlayer.display.domain.model;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 섹션과 그 아이템 전체 — 쓰기 판정 · 응답 조립의 단위다. 아이템은 섹션 안의 순서({@code displayOrder} → 아이디)로 정렬해 든다.
 *
 * <p><b>섹션 규칙</b>({@link #violation()}) — 아래 순서로 처음 걸린 것 하나를 돌려준다.
 * <ol>
 *   <li>기간 — 종료가 시작보다 뒤</li>
 *   <li>링크 — 주소는 상대 경로({@code /…}) 또는 https, 문구만 둘 수 없다</li>
 *   <li>아이템 유형 — 섹션 유형이 받지 않는 유형이 있으면 거절 (히어로 · 스토리 · 디테일의 상품, 상품 픽의 미디어)</li>
 *   <li>아이템 수 — 유형별 범위 ({@link DisplaySectionType}). 미디어 → 상품 순으로 본다</li>
 *   <li>상품 중복 — 한 섹션에 같은 상품 아이디가 두 번</li>
 * </ol>
 * 상품의 존재(숨김 포함)와 미디어 주소 허용은 저장소 · 설정을 봐야 해서 Processor 가 따로 본다.
 */
public record DisplaySectionContent(DisplaySection section, List<DisplayItem> items) {

    private static final Comparator<DisplayItem> ITEM_ORDER = Comparator.comparingInt(DisplayItem::displayOrder).thenComparingLong(DisplayItem::id);

    public DisplaySectionContent {
        Objects.requireNonNull(section, "section");
        items = items.stream().sorted(ITEM_ORDER).toList();
        for (DisplayItem item : items) {
            if (item.sectionId() != section.id()) {
                throw new IllegalArgumentException("다른 섹션의 아이템입니다. sectionId=" + section.id() + ", itemSectionId=" + item.sectionId());
            }
        }
    }

    public Optional<DisplayViolation> violation() {
        if (!section.hasValidPeriod()) {
            return Optional.of(DisplayViolation.of(DisplayViolation.Kind.PERIOD_INVALID));
        }
        if (section.linkUrl() != null && !DisplaySection.isAllowedLinkUrl(section.linkUrl())) {
            return Optional.of(DisplayViolation.of(DisplayViolation.Kind.LINK_URL_INVALID));
        }
        if (section.linkUrl() == null && section.linkLabel() != null) {
            return Optional.of(DisplayViolation.of(DisplayViolation.Kind.LINK_LABEL_WITHOUT_URL));
        }
        DisplaySectionType type = section.sectionType();
        for (DisplayItemType itemType : DisplayItemType.values()) {
            if (!type.allows(itemType) && countOf(itemType) > 0) {
                return Optional.of(new DisplayViolation(DisplayViolation.Kind.ITEM_TYPE_NOT_ALLOWED, itemType));
            }
        }
        for (DisplayItemType itemType : DisplayItemType.values()) {
            long count = countOf(itemType);
            if (count < type.minCountOf(itemType) || count > type.maxCountOf(itemType)) {
                return Optional.of(new DisplayViolation(DisplayViolation.Kind.ITEM_COUNT_OUT_OF_RANGE, itemType));
            }
        }
        Set<Long> seen = new HashSet<>();
        for (DisplayItem item : items) {
            if (item.isProduct() && !seen.add(item.productId())) {
                return Optional.of(DisplayViolation.of(DisplayViolation.Kind.PRODUCT_DUPLICATED));
            }
        }
        return Optional.empty();
    }

    /** 상품 아이템의 상품 아이디 — 아이템 순서, 중복 없이. */
    public Set<Long> productIds() {
        Set<Long> productIds = new LinkedHashSet<>();
        items.stream().filter(DisplayItem::isProduct).forEach(item -> productIds.add(item.productId()));
        return productIds;
    }

    /** 미디어 아이템의 모든 주소 (PC · 모바일 · 포스터). */
    public List<String> mediaUrls() {
        return items.stream().filter(item -> item.media() != null).flatMap(item -> item.media().urls().stream()).toList();
    }

    private long countOf(DisplayItemType itemType) {
        return items.stream().filter(item -> item.itemType() == itemType).count();
    }
}
