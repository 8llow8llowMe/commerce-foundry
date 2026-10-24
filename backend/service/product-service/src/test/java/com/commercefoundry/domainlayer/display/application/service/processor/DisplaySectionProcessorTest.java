package com.commercefoundry.domainlayer.display.application.service.processor;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import com.commercefoundry.domainlayer.display.application.exception.DisplayErrorCode;
import com.commercefoundry.domainlayer.display.application.exception.DisplayException;
import com.commercefoundry.domainlayer.display.application.info.DisplayMainInfo;
import com.commercefoundry.domainlayer.display.application.info.DisplaySectionAdminInfo;
import com.commercefoundry.domainlayer.display.application.port.out.DisplaySectionRepositoryPort;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItem;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplayMedia;
import com.commercefoundry.domainlayer.display.domain.model.DisplayMediaType;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySection;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionContent;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductSummary;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DisplaySectionProcessorTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final String BASE = "https://cdn.example.com/media";
    private static final String IMAGE_URL = BASE + "/displays/hero.jpg";

    private final DisplaySectionRepositoryPort port = mock(DisplaySectionRepositoryPort.class);
    private final DisplaySectionProcessor processor = processor(BASE);

    @Nested
    @DisplayName("고객 메인 읽기")
    class LiveContents {

        @Test
        @DisplayName("지금 보이는 섹션만 고른다 — 숨김 · 시작 전 · 종료 시각과 같은 순간은 빠지고 시작 시각과 같은 순간은 든다. 아이템은 보이는 섹션만 한 번에 읽는다")
        void filtersByLiveCondition() {
            given(port.findAll()).willReturn(List.of(
                section(1L, 0, true, null, null),
                section(2L, 1, false, null, null),
                section(3L, 2, true, NOW, null),
                section(4L, 3, true, NOW.plusNanos(1_000), null),
                section(5L, 4, true, null, NOW),
                section(6L, 5, true, null, NOW.plusNanos(1_000))
            ));
            given(port.findItemsBySectionIds(anyCollection())).willReturn(List.of());

            List<DisplaySectionContent> contents = processor.getLiveContents();

            assertThat(contents).extracting(content -> content.section().id()).containsExactly(1L, 3L, 6L);
            verify(port, times(1)).findAll();
            verify(port, times(1)).findItemsBySectionIds(List.of(1L, 3L, 6L));
        }

        @Test
        @DisplayName("보이는 섹션이 없으면 아이템을 읽지 않는다")
        void noLiveSectionReadsNoItems() {
            given(port.findAll()).willReturn(List.of(section(1L, 0, false, null, null)));

            assertThat(processor.getLiveContents()).isEmpty();
            verify(port, never()).findItemsBySectionIds(anyCollection());
        }

        @Test
        @DisplayName("아이템을 섹션별로 나누고 섹션 안 순서(노출 순서 → 아이디)로 담는다")
        void groupsItemsBySection() {
            given(port.findAll()).willReturn(List.of(section(1L, 0, true, null, null), section(2L, 1, true, null, null)));
            given(port.findItemsBySectionIds(anyCollection())).willReturn(List.of(
                productItem(12L, 2L, 1, 900L), productItem(11L, 1L, 1, 800L), productItem(10L, 1L, 0, 700L), productItem(9L, 2L, 1, 600L)
            ));

            List<DisplaySectionContent> contents = processor.getLiveContents();

            assertThat(contents.get(0).items()).extracting(DisplayItem::id).containsExactly(10L, 11L);
            assertThat(contents.get(1).items()).extracting(DisplayItem::id).containsExactly(9L, 12L);
            assertThat(processor.productIdsOf(contents)).containsExactly(700L, 800L, 600L, 900L);
        }
    }

    @Nested
    @DisplayName("고객 메인 응답 모양 toMainInfo")
    class MainInfo {

        @Test
        @DisplayName("보이지 않는 상품(카드 맵에 없음)의 아이템은 빠지고, 상품이 모두 빠진 상품 픽은 빈 목록으로 남는다. 미디어 아이템은 그대로다")
        void dropsInvisibleProductsKeepsSection() {
            DisplaySectionContent pick = new DisplaySectionContent(
                new DisplaySection(1L, DisplaySectionType.PRODUCT_PICK, "픽", null, null, "/products", "더 보기", 0, true, null, null),
                List.of(productItem(10L, 1L, 0, 700L), productItem(11L, 1L, 1, 800L))
            );
            DisplaySectionContent emptyPick = new DisplaySectionContent(
                new DisplaySection(2L, DisplaySectionType.PRODUCT_PICK, "빈 픽", null, null, null, null, 1, true, null, null),
                List.of(productItem(20L, 2L, 0, 999L))
            );
            DisplaySectionContent hero = new DisplaySectionContent(
                new DisplaySection(3L, DisplaySectionType.HERO, null, null, null, null, null, 2, true, null, null),
                List.of(new DisplayItem(30L, 3L, 0, DisplayItemType.MEDIA, image(), null, "캡션"))
            );

            DisplayMainInfo info = processor.toMainInfo(List.of(pick, emptyPick, hero), Map.of(800L, summary(800L)));

            assertThat(info.sections()).extracting(DisplayMainInfo.Section::sectionId).containsExactly(1L, 2L, 3L);
            assertThat(info.sections().get(0).items()).extracting(DisplayMainInfo.Element::itemId).containsExactly(11L);
            DisplayMainInfo.Product product = info.sections().get(0).items().getFirst().product();
            assertThat(product.productId()).isEqualTo(800L);
            assertThat(product.salePrice()).isEqualTo(Money.of(39_000, KRW));
            assertThat(product.saleStatus()).isEqualTo(SaleAvailability.ON_SALE);
            assertThat(product.thumbnail().imageId()).isEqualTo(5L);
            assertThat(info.sections().get(1).items()).as("상품이 모두 숨겨져도 섹션은 남는다").isEmpty();
            assertThat(info.sections().get(2).items().getFirst().media().pcUrl()).isEqualTo(IMAGE_URL);
            assertThat(info.sections().get(2).items().getFirst().caption()).isEqualTo("캡션");
        }
    }

    @Nested
    @DisplayName("어드민 응답 모양 toAdminInfos")
    class AdminInfo {

        @Test
        @DisplayName("isLive 는 시계 기준이고, 상품은 숨김 포함 이름 · 노출 여부를 싣는다 — 행이 없는 상품은 이름 null · 노출 false")
        void liveAndProductReference() {
            DisplaySectionContent live = new DisplaySectionContent(section(1L, 0, true, null, null), List.of(productItem(10L, 1L, 0, 700L), productItem(11L, 1L, 1, 701L)));
            DisplaySectionContent ended = new DisplaySectionContent(section(2L, 1, true, null, NOW), List.of());
            Product hidden = ProductFixtures.product(700L, SaleStatus.ON_SALE, false, false);

            List<DisplaySectionAdminInfo> infos = processor.toAdminInfos(List.of(live, ended), Map.of(700L, hidden));

            assertThat(infos).extracting(DisplaySectionAdminInfo::live).containsExactly(true, false);
            assertThat(infos.getFirst().items()).extracting(element -> element.product().name(), element -> element.product().visible())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(hidden.name(), false), org.assertj.core.groups.Tuple.tuple(null, false));
        }
    }

    @Nested
    @DisplayName("쓰기 판정 draftOf")
    class Draft {

        @Test
        @DisplayName("섹션 규칙 위반은 유형별 오류 코드다 — 받지 않는 유형 047, 수 범위 048(메시지에 범위), 중복 049, 기간 051, 링크 052 · 053")
        void mapsViolations() {
            assertError(() -> processor.draftOf(command(DisplaySectionType.HERO, List.of(mediaItem(), productCommand(1L)))),
                DisplayErrorCode.DISPLAY_ITEM_TYPE_NOT_ALLOWED, "HERO 섹션에는 PRODUCT 아이템을 둘 수 없습니다.");
            assertError(() -> processor.draftOf(base(DisplaySectionType.STORY).startAt(NOW.plusNanos(1)).endAt(NOW.plusNanos(2)).items(List.of()).build()),
                DisplayErrorCode.DISPLAY_PERIOD_INVALID, null);
            assertError(() -> processor.draftOf(command(DisplaySectionType.PRODUCT_PICK, List.of())),
                DisplayErrorCode.DISPLAY_ITEM_COUNT_OUT_OF_RANGE, "PRODUCT_PICK 섹션의 PRODUCT 아이템은 1~20개여야 합니다.");
            assertError(() -> processor.draftOf(command(DisplaySectionType.PRODUCT_PICK, List.of(productCommand(1L), productCommand(1L)))),
                DisplayErrorCode.DISPLAY_PRODUCT_DUPLICATED, null);
            assertError(() -> processor.draftOf(base(DisplaySectionType.STORY).startAt(NOW).endAt(NOW).items(List.of()).build()),
                DisplayErrorCode.DISPLAY_PERIOD_INVALID, null);
            assertError(() -> processor.draftOf(base(DisplaySectionType.STORY).linkUrl("javascript:alert(1)").items(List.of()).build()),
                DisplayErrorCode.DISPLAY_LINK_URL_INVALID, null);
            assertError(() -> processor.draftOf(base(DisplaySectionType.STORY).linkLabel("보기").items(List.of()).build()),
                DisplayErrorCode.DISPLAY_LINK_LABEL_WITHOUT_URL, null);
        }

        @Test
        @DisplayName("미디어 주소가 공개 주소 밖이면 054 이고, 메시지에 공개 주소를 싣지 않는다")
        void mediaUrlNotAllowed() {
            DisplaySectionUpsertCommand.Item outside = DisplaySectionUpsertCommand.Item.builder().itemType(DisplayItemType.MEDIA)
                .media(DisplaySectionUpsertCommand.Media.builder().mediaType(DisplayMediaType.IMAGE).pcUrl("https://evil.example.com/a.jpg").build()).build();

            assertThatThrownBy(() -> processor.draftOf(command(DisplaySectionType.HERO, List.of(outside))))
                .isInstanceOf(DisplayException.class)
                .satisfies(exception -> assertThat(((DisplayException) exception).getErrorCode()).isEqualTo(DisplayErrorCode.DISPLAY_MEDIA_URL_NOT_ALLOWED))
                .hasMessageNotContaining(BASE);
        }

        @Test
        @DisplayName("공개 주소가 설정되지 않았으면 미디어가 있는 요청은 055(503)이고, 미디어 없는 요청은 통과한다")
        void storageNotConfigured() {
            DisplaySectionProcessor closed = processor("");

            assertError(() -> closed.draftOf(command(DisplaySectionType.HERO, List.of(mediaItem()))), DisplayErrorCode.DISPLAY_MEDIA_STORAGE_NOT_CONFIGURED, null);
            assertThat(closed.draftOf(command(DisplaySectionType.PRODUCT_PICK, List.of(productCommand(1L)))).productIds()).containsExactly(1L);
        }

        @Test
        @DisplayName("통과하면 받은 순서가 아이템 순서인 초안을 돌려준다")
        void draftKeepsOrder() {
            DisplaySectionContent draft = processor.draftOf(command(DisplaySectionType.LOOK, List.of(productCommand(3L), mediaItem(), productCommand(2L))));

            assertThat(draft.items()).extracting(DisplayItem::displayOrder).containsExactly(0, 1, 2);
            assertThat(draft.productIds()).containsExactly(3L, 2L);
        }
    }

    @Nested
    @DisplayName("잠금 안의 판정 · 쓰기")
    class Writes {

        @Test
        @DisplayName("섹션이 50개면 생성은 056(409)이다")
        void sectionLimit() {
            List<DisplaySection> fifty = IntStream.range(0, DisplaySectionProcessor.MAX_SECTION_COUNT).mapToObj(index -> section(index + 1L, index, true, null, null)).toList();

            assertError(() -> processor.validateCanCreate(fifty), DisplayErrorCode.DISPLAY_SECTION_LIMIT_EXCEEDED, "전시 섹션은 최대 50개까지 만들 수 있습니다.");
            processor.validateCanCreate(fifty.subList(0, 49));
        }

        @Test
        @DisplayName("없는 상품이 있으면 050(400)이고 메시지에 없는 아이디를 모두 싣는다 — 숨긴 상품은 있는 상품이다")
        void productsMustExist() {
            Product hidden = ProductFixtures.product(1L, SaleStatus.ON_SALE, false, false);

            assertError(() -> processor.validateProductsExist(new java.util.LinkedHashSet<>(List.of(1L, 2L, 3L)), Map.of(1L, hidden)),
                DisplayErrorCode.DISPLAY_PRODUCT_NOT_FOUND, "존재하지 않는 상품이 있습니다: 2, 3");
            processor.validateProductsExist(java.util.Set.of(1L), Map.of(1L, hidden));
        }

        @Test
        @DisplayName("생성은 맨 뒤 순서(최대 + 1, 없으면 0)와 새 아이디로 INSERT 한다")
        void createAppendsLast() {
            DisplaySectionContent draft = processor.draftOf(command(DisplaySectionType.PRODUCT_PICK, List.of(productCommand(1L))));

            DisplaySectionContent first = processor.create(draft, List.of());
            DisplaySectionContent next = processor.create(draft, List.of(section(1L, 0, true, null, null), section(2L, 7, true, null, null)));

            assertThat(first.section().displayOrder()).isZero();
            assertThat(next.section().displayOrder()).isEqualTo(8);
            assertThat(next.section().id()).isPositive();
            assertThat(next.items()).allSatisfy(item -> {
                assertThat(item.id()).isPositive();
                assertThat(item.sectionId()).isEqualTo(next.section().id());
            });
            verify(port, times(2)).insert(any());
        }

        @Test
        @DisplayName("교체는 섹션 아이디 · 노출 순서를 지키고 아이템 아이디를 새로 발급한다")
        void replaceKeepsIdentity() {
            DisplaySection current = section(42L, 3, true, null, null);
            DisplaySectionContent draft = processor.draftOf(command(DisplaySectionType.PRODUCT_PICK, List.of(productCommand(1L))));

            DisplaySectionContent replaced = processor.replace(current, draft);

            assertThat(replaced.section().id()).isEqualTo(42L);
            assertThat(replaced.section().displayOrder()).isEqualTo(3);
            assertThat(replaced.items().getFirst().id()).isNotZero();
            verify(port).replace(replaced);
        }

        @Test
        @DisplayName("순서 변경은 받은 순서대로 0..n-1 을 매긴다")
        void reorder() {
            List<DisplaySection> locked = List.of(section(1L, 0, true, null, null), section(2L, 1, true, null, null), section(3L, 2, true, null, null));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<Long, Integer>> captor = ArgumentCaptor.forClass(Map.class);

            processor.reorder(locked, List.of(3L, 1L, 2L));

            verify(port).changeDisplayOrders(captor.capture());
            assertThat(captor.getValue()).containsExactly(Map.entry(3L, 0), Map.entry(1L, 1), Map.entry(2L, 2));
        }

        @Test
        @DisplayName("순서 변경 본문이 현재 집합과 다르면(누락 · 중복 · 모르는 아이디) 057(400)이고 아무것도 바꾸지 않는다")
        void reorderRequiresExactSet() {
            List<DisplaySection> locked = List.of(section(1L, 0, true, null, null), section(2L, 1, true, null, null));

            for (List<Long> body : List.of(List.of(1L), List.of(1L, 1L), List.of(1L, 2L, 3L), List.of(1L, 3L), List.<Long>of())) {
                assertError(() -> processor.reorder(locked, body), DisplayErrorCode.DISPLAY_SECTION_ORDER_MISMATCH, null);
            }
            verify(port, never()).changeDisplayOrders(any());
            processor.reorder(List.of(), List.of());
        }

        @Test
        @DisplayName("잠근 목록에 없는 섹션은 046(404)이다")
        void requireSection() {
            assertError(() -> processor.requireSection(List.of(section(1L, 0, true, null, null)), 2L), DisplayErrorCode.NOT_FOUND_DISPLAY_SECTION, null);
        }
    }

    /** 필드 {@link #port} 가 먼저 초기화되므로 필드 초기화에서 불러도 같은 가짜 포트를 쓴다. */
    private DisplaySectionProcessor processor(String base) {
        return new DisplaySectionProcessor(port, new SnowflakeIdGenerator(1, 1), MediaUrlPolicy.of(base), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, DisplayErrorCode errorCode, String message) {
        var assertion = assertThatThrownBy(call).isInstanceOf(DisplayException.class)
            .satisfies(exception -> assertThat(((DisplayException) exception).getErrorCode()).isEqualTo(errorCode));
        if (message != null) {
            assertion.hasMessage(message);
        }
    }

    private static DisplaySection section(long id, int displayOrder, boolean visible, Instant startAt, Instant endAt) {
        return new DisplaySection(id, DisplaySectionType.STORY, "섹션" + id, null, null, null, null, displayOrder, visible, startAt, endAt);
    }

    private static DisplayItem productItem(long id, long sectionId, int displayOrder, long productId) {
        return new DisplayItem(id, sectionId, displayOrder, DisplayItemType.PRODUCT, null, productId, null);
    }

    private static DisplayMedia image() {
        return new DisplayMedia(DisplayMediaType.IMAGE, IMAGE_URL, null, null, null, "대체");
    }

    private static ProductSummary summary(long productId) {
        return new ProductSummary(
            productId, "상품" + productId, ProductFixtures.image(5L, productId, com.commercefoundry.domainlayer.product.domain.model.ImageType.THUMBNAIL, 0),
            Money.of(39_000, KRW), Money.of(45_000, KRW), 13, false, SaleAvailability.ON_SALE, false
        );
    }

    private static DisplaySectionUpsertCommand.DisplaySectionUpsertCommandBuilder base(DisplaySectionType type) {
        return DisplaySectionUpsertCommand.builder().sectionType(type).title("제목").visible(true);
    }

    private static DisplaySectionUpsertCommand command(DisplaySectionType type, Collection<DisplaySectionUpsertCommand.Item> items) {
        return base(type).items(new ArrayList<>(items)).build();
    }

    private static DisplaySectionUpsertCommand.Item mediaItem() {
        return DisplaySectionUpsertCommand.Item.builder().itemType(DisplayItemType.MEDIA)
            .media(DisplaySectionUpsertCommand.Media.builder().mediaType(DisplayMediaType.IMAGE).pcUrl(IMAGE_URL).build()).build();
    }

    private static DisplaySectionUpsertCommand.Item productCommand(long productId) {
        return DisplaySectionUpsertCommand.Item.builder().itemType(DisplayItemType.PRODUCT).productId(productId).build();
    }
}
