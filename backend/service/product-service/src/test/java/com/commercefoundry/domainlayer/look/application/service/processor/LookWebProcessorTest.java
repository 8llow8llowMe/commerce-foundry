package com.commercefoundry.domainlayer.look.application.service.processor;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.look.application.exception.LookErrorCode;
import com.commercefoundry.domainlayer.look.application.exception.LookException;
import com.commercefoundry.domainlayer.look.application.info.LookInfo;
import com.commercefoundry.domainlayer.look.application.info.LookProductInfo;
import com.commercefoundry.domainlayer.look.application.port.out.LookRepositoryPort;
import com.commercefoundry.domainlayer.look.domain.model.Look;
import com.commercefoundry.domainlayer.look.domain.model.LookContent;
import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import com.commercefoundry.domainlayer.look.domain.model.LookItem;
import com.commercefoundry.domainlayer.product.domain.model.ProductSummary;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

class LookWebProcessorTest {

    private static final LookImage IMAGE = new LookImage("https://cdn.example.com/media/a.jpg", 1200, 1600, "대체");

    private final LookRepositoryPort port = mock(LookRepositoryPort.class);
    private final LookWebProcessor processor = processor(true);

    @Nested
    @DisplayName("기능 플래그 끔 — 데이터는 두고 응답에서만 숨긴다 (저장소를 읽지 않는다)")
    class FlagOff {

        private final LookRepositoryPort offPort = mock(LookRepositoryPort.class);
        private final LookWebProcessor off = new LookWebProcessor(offPort, new StoreProperties(StoreProperties.Features.DISABLED));

        @Test
        @DisplayName("목록은 빈 쪽 · 상세는 404 LOOK_001 · 상품별 룩은 빈 목록이다")
        void allPathsHidden() {
            Slice<LookContent> slice = off.getVisibleSlice(0, 20);

            assertThat(slice.getContent()).isEmpty();
            assertThat(slice.hasNext()).isFalse();
            assertError(() -> off.getVisibleContent(1L), LookErrorCode.NOT_FOUND_LOOK);
            assertThat(off.getVisibleContentsOfProduct(700L)).isEmpty();
            verifyNoInteractions(offPort);
        }
    }

    @Nested
    @DisplayName("목록 · 상품별 룩")
    class Lists {

        @Test
        @DisplayName("목록은 저장소 순서를 지키고 아이템은 in 절 한 번으로 붙이며 hasNext 를 그대로 둔다 — 아이템이 빈(읽는 사이 삭제된) 룩은 빠진다")
        void sliceWithItems() {
            given(port.findVisibleSlice(0, 3)).willReturn(new SliceImpl<>(List.of(look(2L, true), look(1L, true), look(3L, true)), PageRequest.of(0, 3), true));
            given(port.findItemsByLookIds(List.of(2L, 1L, 3L))).willReturn(List.of(item(11L, 1L, 100L, 0), item(22L, 2L, 201L, 1), item(21L, 2L, 200L, 0)));

            Slice<LookContent> slice = processor.getVisibleSlice(0, 3);

            assertThat(slice.getContent()).extracting(content -> content.look().id()).containsExactly(2L, 1L);
            assertThat(slice.getContent().getFirst().productIds()).as("룩 안 순서").containsExactly(200L, 201L);
            assertThat(slice.hasNext()).isTrue();
        }

        @Test
        @DisplayName("빈 쪽이면 아이템을 읽지 않는다")
        void emptySliceReadsNoItems() {
            given(port.findVisibleSlice(anyInt(), anyInt())).willReturn(new SliceImpl<>(List.of(), PageRequest.of(5, 20), false));

            assertThat(processor.getVisibleSlice(5, 20).getContent()).isEmpty();
            assertThat(processor.getVisibleContentsOfProduct(700L)).isEmpty();
        }

        @Test
        @DisplayName("상품별 룩은 보이는 룩만(저장소 판정) 아이템을 붙여 낸다")
        void looksOfProduct() {
            given(port.findVisibleByProductId(700L)).willReturn(List.of(look(3L, true)));
            given(port.findItemsByLookIds(anyCollection())).willReturn(List.of(item(31L, 3L, 700L, 0)));

            assertThat(processor.getVisibleContentsOfProduct(700L)).singleElement().satisfies(content -> assertThat(content.look().id()).isEqualTo(3L));
        }
    }

    @Nested
    @DisplayName("상세")
    class Detail {

        @Test
        @DisplayName("보이는 룩은 아이템과 함께 낸다")
        void visible() {
            given(port.findById(1L)).willReturn(Optional.of(look(1L, true)));
            given(port.findItemsByLookIds(List.of(1L))).willReturn(List.of(item(11L, 1L, 100L, 0)));

            assertThat(processor.getVisibleContent(1L).productIds()).containsExactly(100L);
        }

        @Test
        @DisplayName("없는 룩 · 숨긴 룩은 같은 404 LOOK_001 이다 — 숨긴 룩은 아이템을 읽지 않는다")
        void missingOrHidden() {
            given(port.findById(1L)).willReturn(Optional.empty());
            given(port.findById(2L)).willReturn(Optional.of(look(2L, false)));

            assertError(() -> processor.getVisibleContent(1L), LookErrorCode.NOT_FOUND_LOOK);
            assertError(() -> processor.getVisibleContent(2L), LookErrorCode.NOT_FOUND_LOOK);
            verify(port, never()).findItemsByLookIds(anyCollection());
        }

        @Test
        @DisplayName("룩 행을 읽은 뒤 아이템을 읽기 전에 삭제되어 아이템이 비면 500 이 아니라 404 LOOK_001 이다")
        void deletedBetweenReads() {
            given(port.findById(1L)).willReturn(Optional.of(look(1L, true)));
            given(port.findItemsByLookIds(List.of(1L))).willReturn(List.of());

            assertError(() -> processor.getVisibleContent(1L), LookErrorCode.NOT_FOUND_LOOK);
        }
    }

    @Nested
    @DisplayName("응답 정보")
    class Infos {

        @Test
        @DisplayName("숨긴 · 없는 상품은 빠지고 판매 종료 · 품절은 판매 상태로 남는다 — 구성 순서를 지킨다")
        void dropsHiddenKeepsStatus() {
            LookContent content = new LookContent(look(1L, true), List.of(item(11L, 1L, 300L, 0), item(12L, 1L, 999L, 1), item(13L, 1L, 100L, 2)));
            Map<Long, ProductSummary> visible = Map.of(100L, summary(100L, SaleAvailability.SOLD_OUT), 300L, summary(300L, SaleAvailability.STOP_SELLING));

            LookInfo info = processor.toInfo(content, visible);

            assertThat(info.products()).extracting(LookProductInfo::productId).containsExactly(300L, 100L);
            assertThat(info.products()).extracting(LookProductInfo::saleStatus).containsExactly(SaleAvailability.STOP_SELLING, SaleAvailability.SOLD_OUT);
            assertThat(info.image()).isEqualTo(IMAGE);
        }

        @Test
        @DisplayName("구성 상품이 모두 숨겨져도 룩은 남고 products 가 빈 목록이다")
        void allHiddenKeepsLook() {
            LookContent content = new LookContent(look(1L, true), List.of(item(11L, 1L, 300L, 0)));

            assertThat(processor.toInfos(List.of(content), Map.of())).singleElement().satisfies(info -> assertThat(info.products()).isEmpty());
        }
    }

    private LookWebProcessor processor(boolean looks) {
        return new LookWebProcessor(port, new StoreProperties(new StoreProperties.Features(false, false, false, looks)));
    }

    private static Look look(long id, boolean visible) {
        return new Look(id, "룩" + id, "설명", IMAGE, visible, 0);
    }

    private static LookItem item(long id, long lookId, long productId, int displayOrder) {
        return new LookItem(id, lookId, productId, displayOrder);
    }

    private static ProductSummary summary(long productId, SaleAvailability saleStatus) {
        return new ProductSummary(productId, "상품" + productId, null, null, Money.of(10_000L, KRW), null, null, false, saleStatus, false, false);
    }

    private static void assertError(ThrowingCallable call, LookErrorCode errorCode) {
        assertThatThrownBy(call)
            .isInstanceOf(LookException.class)
            .satisfies(exception -> assertThat(((LookException) exception).getErrorCode()).isEqualTo(errorCode));
    }
}
