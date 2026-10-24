package com.commercefoundry.domainlayer.display.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DisplaySectionContentTest {

    private static final long SECTION_ID = 10L;
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Nested
    @DisplayName("유형별 아이템 수 — 하한 · 상한 포함")
    class ItemCounts {

        @ParameterizedTest(name = "{0}: 미디어 {1} · 상품 {2} → 허용")
        @CsvSource({
            "HERO, 1, 0", "HERO, 10, 0",
            "PRODUCT_PICK, 0, 1", "PRODUCT_PICK, 0, 20",
            "STORY, 0, 0", "STORY, 10, 0",
            "LOOK, 1, 0", "LOOK, 10, 20",
            "DETAIL, 1, 0", "DETAIL, 10, 0"
        })
        @DisplayName("범위 안이면 위반이 없다")
        void withinRange(DisplaySectionType type, int mediaCount, int productCount) {
            assertThat(content(type, mediaCount, productCount).violation()).isEmpty();
        }

        @ParameterizedTest(name = "{0}: 미디어 {1} · 상품 {2} → {3} 수 위반")
        @CsvSource({
            "HERO, 0, 0, MEDIA", "HERO, 11, 0, MEDIA",
            "PRODUCT_PICK, 0, 0, PRODUCT", "PRODUCT_PICK, 0, 21, PRODUCT",
            "STORY, 11, 0, MEDIA",
            "LOOK, 0, 1, MEDIA", "LOOK, 11, 0, MEDIA", "LOOK, 1, 21, PRODUCT",
            "DETAIL, 0, 0, MEDIA", "DETAIL, 11, 0, MEDIA"
        })
        @DisplayName("범위 밖이면 그 아이템 유형의 수 위반이다")
        void outOfRange(DisplaySectionType type, int mediaCount, int productCount, DisplayItemType violatedType) {
            assertThat(content(type, mediaCount, productCount).violation())
                .contains(new DisplayViolation(DisplayViolation.Kind.ITEM_COUNT_OUT_OF_RANGE, violatedType));
        }

        @ParameterizedTest(name = "{0}: 미디어 {1} · 상품 {2} → {3} 금지")
        @CsvSource({"HERO, 1, 1, PRODUCT", "PRODUCT_PICK, 1, 1, MEDIA", "STORY, 0, 1, PRODUCT", "DETAIL, 1, 1, PRODUCT"})
        @DisplayName("받지 않는 유형이 하나라도 있으면 수보다 먼저 유형 위반이다")
        void typeNotAllowed(DisplaySectionType type, int mediaCount, int productCount, DisplayItemType forbidden) {
            assertThat(content(type, mediaCount, productCount).violation())
                .contains(new DisplayViolation(DisplayViolation.Kind.ITEM_TYPE_NOT_ALLOWED, forbidden));
        }
    }

    @Test
    @DisplayName("한 섹션에 같은 상품이 두 번 있으면 중복 위반이다")
    void duplicatedProduct() {
        List<DisplayItem> items = List.of(product(1L, 0, 7L), product(2L, 1, 8L), product(3L, 2, 7L));

        assertThat(new DisplaySectionContent(section(DisplaySectionType.PRODUCT_PICK, null, null, null, null), items).violation())
            .contains(DisplayViolation.of(DisplayViolation.Kind.PRODUCT_DUPLICATED));
    }

    @Nested
    @DisplayName("기간 · 링크")
    class SectionFields {

        @Test
        @DisplayName("종료가 시작보다 뒤가 아니면 기간 위반이다 — 아이템 규칙보다 먼저 본다")
        void period() {
            DisplaySectionContent content = new DisplaySectionContent(section(DisplaySectionType.HERO, NOW, NOW, null, null), List.of());

            assertThat(content.violation()).contains(DisplayViolation.of(DisplayViolation.Kind.PERIOD_INVALID));
        }

        @Test
        @DisplayName("허용되지 않는 링크 주소는 링크 위반이다")
        void linkUrl() {
            DisplaySectionContent content = new DisplaySectionContent(section(DisplaySectionType.STORY, null, null, "//evil.example.com", "보기"), List.of());

            assertThat(content.violation()).contains(DisplayViolation.of(DisplayViolation.Kind.LINK_URL_INVALID));
        }

        @Test
        @DisplayName("주소 없이 문구만 있으면 위반이다")
        void labelWithoutUrl() {
            DisplaySectionContent content = new DisplaySectionContent(section(DisplaySectionType.STORY, null, null, null, "보기"), List.of());

            assertThat(content.violation()).contains(DisplayViolation.of(DisplayViolation.Kind.LINK_LABEL_WITHOUT_URL));
        }
    }

    @Test
    @DisplayName("아이템은 노출 순서 → 아이디로 정렬해 들고, 상품 아이디 · 미디어 주소를 순서대로 꺼낸다")
    void ordersItemsAndCollects() {
        DisplayMedia video = new DisplayMedia(DisplayMediaType.VIDEO, "https://cdn.example.com/v.mp4", null, "https://cdn.example.com/p.jpg", null, null);
        List<DisplayItem> items = List.of(
            product(5L, 2, 70L),
            new DisplayItem(4L, SECTION_ID, 0, DisplayItemType.MEDIA, video, null, null),
            product(3L, 1, 80L),
            product(2L, 1, 90L)
        );

        DisplaySectionContent content = new DisplaySectionContent(section(DisplaySectionType.LOOK, null, null, null, null), items);

        assertThat(content.items()).extracting(DisplayItem::id).containsExactly(4L, 2L, 3L, 5L);
        assertThat(content.productIds()).containsExactly(90L, 80L, 70L);
        assertThat(content.mediaUrls()).containsExactly("https://cdn.example.com/v.mp4", "https://cdn.example.com/p.jpg");
        assertThat(content.violation()).isEmpty();
    }

    @Test
    @DisplayName("다른 섹션의 아이템은 담을 수 없다")
    void foreignItem() {
        DisplayItem foreign = new DisplayItem(1L, SECTION_ID + 1, 0, DisplayItemType.PRODUCT, null, 1L, null);

        assertThatThrownBy(() -> new DisplaySectionContent(section(DisplaySectionType.PRODUCT_PICK, null, null, null, null), List.of(foreign)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("아이템 · 미디어 모양")
    class Shapes {

        @Test
        @DisplayName("미디어 아이템에 상품 아이디가, 상품 아이템에 미디어가 있으면 만들 수 없다")
        void itemShape() {
            assertThatThrownBy(() -> new DisplayItem(1L, SECTION_ID, 0, DisplayItemType.MEDIA, image(), 1L, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DisplayItem(1L, SECTION_ID, 0, DisplayItemType.PRODUCT, image(), 1L, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DisplayItem(1L, SECTION_ID, 0, DisplayItemType.PRODUCT, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("영상은 PC 포스터가 필요하고, 이미지는 포스터를 갖지 않는다")
        void posterShape() {
            assertThatThrownBy(() -> new DisplayMedia(DisplayMediaType.VIDEO, "https://cdn.example.com/v.mp4", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DisplayMedia(DisplayMediaType.IMAGE, "https://cdn.example.com/a.jpg", null, null, "https://cdn.example.com/p.jpg", null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static DisplaySectionContent content(DisplaySectionType type, int mediaCount, int productCount) {
        List<DisplayItem> items = new ArrayList<>();
        int order = 0;
        for (int index = 0; index < mediaCount; index++) {
            items.add(new DisplayItem(order + 1L, SECTION_ID, order, DisplayItemType.MEDIA, image(), null, null));
            order++;
        }
        for (long productId : LongStream.rangeClosed(1, productCount).toArray()) {
            items.add(product(order + 1L, order, productId));
            order++;
        }
        return new DisplaySectionContent(section(type, null, null, null, null), items);
    }

    private static DisplaySection section(DisplaySectionType type, Instant startAt, Instant endAt, String linkUrl, String linkLabel) {
        return new DisplaySection(SECTION_ID, type, "제목", null, null, linkUrl, linkLabel, 0, true, startAt, endAt);
    }

    private static DisplayItem product(long id, int displayOrder, long productId) {
        return new DisplayItem(id, SECTION_ID, displayOrder, DisplayItemType.PRODUCT, null, productId, null);
    }

    private static DisplayMedia image() {
        return new DisplayMedia(DisplayMediaType.IMAGE, "https://cdn.example.com/a.jpg", null, null, null, null);
    }
}
