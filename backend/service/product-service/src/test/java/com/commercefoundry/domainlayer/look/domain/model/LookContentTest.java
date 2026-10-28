package com.commercefoundry.domainlayer.look.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LookContentTest {

    private static final Look LOOK = new Look(1L, "가을 룩", null, new LookImage("https://cdn.example.com/a.jpg", null, null, null), true, 0);

    @Test
    @DisplayName("아이템은 순서 → 아이디로 정렬되고 productIds 는 그 순서다")
    void sortsItems() {
        LookContent content = new LookContent(LOOK, List.of(item(12L, 300L, 1), item(11L, 100L, 0), item(10L, 200L, 1)));

        assertThat(content.items()).extracting(LookItem::id).containsExactly(11L, 10L, 12L);
        assertThat(content.productIds()).containsExactly(100L, 200L, 300L);
    }

    @Test
    @DisplayName("구성 상품은 1~20개다 — 0개 · 21개는 불변식 위반이다")
    void itemCountRange() {
        assertThatThrownBy(() -> new LookContent(LOOK, List.of())).isInstanceOf(IllegalArgumentException.class);
        List<LookItem> twentyOne = LongStream.range(0, LookLimits.MAX_ITEM_COUNT + 1).mapToObj(index -> item(index + 1, index + 100, (int) index)).toList();
        assertThatThrownBy(() -> new LookContent(LOOK, twentyOne)).isInstanceOf(IllegalArgumentException.class);

        List<LookItem> twenty = twentyOne.subList(0, LookLimits.MAX_ITEM_COUNT);
        assertThat(new LookContent(LOOK, twenty).items()).hasSize(LookLimits.MAX_ITEM_COUNT);
    }

    @Test
    @DisplayName("같은 상품이 두 번이면 불변식 위반이고, hasDuplicateProduct 가 같은 판정을 미리 한다")
    void duplicateProduct() {
        assertThatThrownBy(() -> new LookContent(LOOK, List.of(item(1L, 100L, 0), item(2L, 100L, 1)))).isInstanceOf(IllegalArgumentException.class);

        assertThat(LookContent.hasDuplicateProduct(List.of(100L, 200L, 100L))).isTrue();
        assertThat(LookContent.hasDuplicateProduct(List.of(100L, 200L))).isFalse();
    }

    @Test
    @DisplayName("다른 룩의 아이템은 받지 않는다")
    void foreignItem() {
        assertThatThrownBy(() -> new LookContent(LOOK, List.of(new LookItem(1L, 2L, 100L, 0)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("룩 · 이미지 · 아이템 값 규칙 — 순서는 0 이상, 이미지 크기는 양수")
    void valueRules() {
        LookImage image = LOOK.image();
        assertThatThrownBy(() -> new Look(1L, "룩", null, image, true, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LookImage("https://cdn.example.com/a.jpg", 0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LookImage("https://cdn.example.com/a.jpg", null, -1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LookItem(1L, 1L, 100L, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new LookImage("https://cdn.example.com/a.jpg", 1200, 1600, "대체").width()).isEqualTo(1200);
    }

    private static LookItem item(long id, long productId, int displayOrder) {
        return new LookItem(id, LOOK.id(), productId, displayOrder);
    }
}
