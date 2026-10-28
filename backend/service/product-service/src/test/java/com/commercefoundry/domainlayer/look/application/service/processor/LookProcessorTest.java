package com.commercefoundry.domainlayer.look.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.look.application.command.LookUpsertCommand;
import com.commercefoundry.domainlayer.look.application.exception.LookErrorCode;
import com.commercefoundry.domainlayer.look.application.exception.LookException;
import com.commercefoundry.domainlayer.look.application.info.LookAdminInfo;
import com.commercefoundry.domainlayer.look.application.info.LookProductAdminInfo;
import com.commercefoundry.domainlayer.look.application.port.out.LookRepositoryPort;
import com.commercefoundry.domainlayer.look.domain.model.Look;
import com.commercefoundry.domainlayer.look.domain.model.LookContent;
import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import com.commercefoundry.domainlayer.look.domain.model.LookItem;
import com.commercefoundry.domainlayer.look.domain.model.LookLimits;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LookProcessorTest {

    private static final String BASE = "https://cdn.example.com/media";
    private static final String IMAGE_URL = BASE + "/looks/autumn.jpg";

    private final LookRepositoryPort port = mock(LookRepositoryPort.class);
    private final LookProcessor processor = processor(BASE);

    @Nested
    @DisplayName("초안 판정 (잠금 전)")
    class Draft {

        @Test
        @DisplayName("요청을 임시 아이디 초안으로 바꾼다 — 구성 순서는 배열 순서다")
        void draft() {
            LookContent draft = processor.draftOf(command(IMAGE_URL, 300L, 100L, 200L));

            assertThat(draft.look().title()).isEqualTo("가을 룩");
            assertThat(draft.look().image()).isEqualTo(new LookImage(IMAGE_URL, 1200, 1600, "대체"));
            assertThat(draft.productIds()).containsExactly(300L, 100L, 200L);
            assertThat(draft.items()).extracting(LookItem::displayOrder).containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("같은 상품이 두 번이면 400 · LOOK_003 이고 이미지 판정 전에 걸린다")
        void duplicateProduct() {
            LookProcessor unconfigured = processor("");

            assertError(() -> unconfigured.draftOf(command(IMAGE_URL, 100L, 100L)), LookErrorCode.LOOK_PRODUCT_DUPLICATED);
        }

        @Test
        @DisplayName("공개 주소가 설정되지 않았으면 503 · LOOK_006 이다 — 이미지가 필수라 모든 쓰기가 닫힌다")
        void storageNotConfigured() {
            assertError(() -> processor("").draftOf(command(IMAGE_URL, 100L)), LookErrorCode.LOOK_MEDIA_STORAGE_NOT_CONFIGURED);
        }

        @Test
        @DisplayName("공개 주소 밖 · 경로 이동 주소는 400 · LOOK_005 이고 메시지에 공개 주소를 싣지 않는다")
        void urlNotAllowed() {
            for (String url : List.of("https://evil.example.com/a.jpg", BASE + "/../a.jpg", "http://cdn.example.com/media/a.jpg")) {
                assertThatThrownBy(() -> processor.draftOf(command(url, 100L)))
                    .isInstanceOf(LookException.class)
                    .satisfies(exception -> assertThat(((LookException) exception).getErrorCode()).isEqualTo(LookErrorCode.LOOK_IMAGE_URL_NOT_ALLOWED))
                    .message().doesNotContain(BASE);
            }
        }
    }

    @Nested
    @DisplayName("잠근 목록 판정")
    class LockedRules {

        @Test
        @DisplayName("룩이 상한(100)에 닿으면 생성은 409 · LOOK_002 이고 99개면 통과한다")
        void limit() {
            List<Look> full = LongStream.rangeClosed(1, LookLimits.MAX_LOOK_COUNT).mapToObj(id -> look(id, (int) id)).toList();

            assertThatThrownBy(() -> processor.validateCanCreate(full))
                .isInstanceOf(LookException.class)
                .hasMessage("룩은 최대 100개까지 만들 수 있습니다.");
            processor.validateCanCreate(full.subList(0, LookLimits.MAX_LOOK_COUNT - 1));
        }

        @Test
        @DisplayName("잠근 목록에 없는 룩은 404 · LOOK_001 이다")
        void requireLook() {
            assertThat(processor.requireLook(List.of(look(1L, 0)), 1L).id()).isEqualTo(1L);
            assertError(() -> processor.requireLook(List.of(look(1L, 0)), 2L), LookErrorCode.NOT_FOUND_LOOK);
        }

        @Test
        @DisplayName("없는 상품은 400 · LOOK_004 이고 메시지에 그 아이디를 모두 싣는다 — 숨김 · 판매 종료 상품은 있는 상품이다")
        void productsExist() {
            Map<Long, Product> found = Map.of(
                100L, ProductFixtures.product(100L, SaleStatus.STOP_SELLING, false, false),
                200L, ProductFixtures.product(200L)
            );

            processor.validateProductsExist(List.of(100L, 200L), found);
            assertThatThrownBy(() -> processor.validateProductsExist(List.of(300L, 100L, 400L), found))
                .isInstanceOf(LookException.class)
                .hasMessage("존재하지 않는 상품이 있습니다: 300, 400");
        }

        @Test
        @DisplayName("순서 변경 — 현재 집합과 같으면 받은 순서대로 0..n-1, 누락 · 중복 · 모르는 아이디는 400 · LOOK_007 이다")
        @SuppressWarnings("unchecked")
        void reorder() {
            List<Look> locked = List.of(look(1L, 0), look(2L, 1), look(3L, 2));

            processor.reorder(locked, List.of(3L, 1L, 2L));

            ArgumentCaptor<Map<Long, Integer>> captor = ArgumentCaptor.forClass(Map.class);
            verify(port).changeDisplayOrders(captor.capture());
            assertThat(captor.getValue()).containsExactly(Map.entry(3L, 0), Map.entry(1L, 1), Map.entry(2L, 2));

            for (List<Long> wrong : List.of(List.of(1L, 2L), List.of(1L, 2L, 2L), List.of(1L, 2L, 4L), List.of(1L, 2L, 3L, 4L))) {
                assertError(() -> processor.reorder(locked, wrong), LookErrorCode.LOOK_ORDER_MISMATCH);
            }
            processor.reorder(List.of(), List.of());
        }
    }

    @Nested
    @DisplayName("쓰기")
    class Writes {

        @Test
        @DisplayName("생성은 아이디를 발급하고 맨 뒤(최대 + 1) 순서에 둔다 — 룩이 없으면 0")
        void create() {
            LookContent draft = processor.draftOf(command(IMAGE_URL, 100L, 200L));

            LookContent created = processor.create(draft, List.of(look(1L, 4), look(2L, 7)));
            LookContent first = processor.create(draft, List.of());

            assertThat(created.look().id()).isPositive();
            assertThat(created.look().displayOrder()).isEqualTo(8);
            assertThat(created.items()).allSatisfy(item -> {
                assertThat(item.lookId()).isEqualTo(created.look().id());
                assertThat(item.id()).isPositive();
            });
            assertThat(first.look().displayOrder()).isZero();
            verify(port).insert(created);
        }

        @Test
        @DisplayName("교체는 룩 아이디 · 순서를 그대로 두고 아이템 아이디를 새로 발급한다")
        void replace() {
            LookContent draft = processor.draftOf(command(IMAGE_URL, 100L));

            LookContent replaced = processor.replace(look(5L, 3), draft);

            assertThat(replaced.look().id()).isEqualTo(5L);
            assertThat(replaced.look().displayOrder()).isEqualTo(3);
            assertThat(replaced.items()).singleElement().satisfies(item -> assertThat(item.lookId()).isEqualTo(5L));
            verify(port).replace(replaced);
        }
    }

    @Nested
    @DisplayName("읽기 · 어드민 정보")
    class Reads {

        @Test
        @DisplayName("전체 목록은 룩 1번 · 아이템 1번(in 절) 읽고, 룩이 없으면 아이템을 읽지 않는다")
        void readsInBulk() {
            given(port.findAll()).willReturn(List.of(look(1L, 0), look(2L, 1)));
            given(port.findItemsByLookIds(anyCollection())).willReturn(List.of(new LookItem(11L, 1L, 100L, 0), new LookItem(21L, 2L, 200L, 0)));

            List<LookContent> contents = processor.getAllContents();

            assertThat(contents).extracting(content -> content.productIds().getFirst()).containsExactly(100L, 200L);
            assertThat(processor.productIdsOf(contents)).containsExactly(100L, 200L);
            verify(port, times(1)).findItemsByLookIds(List.of(1L, 2L));

            given(port.findAll()).willReturn(List.of());
            assertThat(processor.getAllContents()).isEmpty();
            verify(port, times(1)).findItemsByLookIds(anyCollection());
        }

        @Test
        @DisplayName("단건이 없으면 404 · LOOK_001 이고 아이템을 읽지 않는다")
        void getContentNotFound() {
            given(port.findById(9L)).willReturn(java.util.Optional.empty());

            assertError(() -> processor.getContent(9L), LookErrorCode.NOT_FOUND_LOOK);
            verify(port, never()).findItemsByLookIds(any());
        }

        @Test
        @DisplayName("단건 — 룩 행을 읽은 뒤 아이템을 읽기 전에 삭제가 커밋되어 아이템이 비면 500 이 아니라 404 · LOOK_001 이다")
        void getContentDeletedBetweenReads() {
            given(port.findById(1L)).willReturn(java.util.Optional.of(look(1L, 0)));
            given(port.findItemsByLookIds(List.of(1L))).willReturn(List.of());

            assertError(() -> processor.getContent(1L), LookErrorCode.NOT_FOUND_LOOK);
        }

        @Test
        @DisplayName("목록 — 아이템을 읽기 전에 삭제된 룩(아이템 없음)은 500 없이 결과에서 빠진다")
        void getAllContentsSkipsDeletedBetweenReads() {
            given(port.findAll()).willReturn(List.of(look(1L, 0), look(2L, 1), look(3L, 2)));
            given(port.findItemsByLookIds(anyCollection())).willReturn(List.of(new LookItem(11L, 1L, 100L, 0), new LookItem(31L, 3L, 300L, 0)));

            assertThat(processor.getAllContents()).extracting(content -> content.look().id()).containsExactly(1L, 3L);
        }

        @Test
        @DisplayName("어드민 정보는 상품의 이름 · 노출 · 판매 상태를 담고, 없는 상품은 이름 · 판매 상태 null · 노출 false 다")
        void adminInfo() {
            LookContent content = new LookContent(look(1L, 0), List.of(new LookItem(11L, 1L, 100L, 0), new LookItem(12L, 1L, 999L, 1)));
            Map<Long, Product> products = Map.of(100L, ProductFixtures.product(100L, SaleStatus.STOP_SELLING, false, false));

            LookAdminInfo info = processor.toAdminInfo(content, products);

            assertThat(info.lookId()).isEqualTo(1L);
            assertThat(info.products()).extracting(LookProductAdminInfo::productId).containsExactly(100L, 999L);
            LookProductAdminInfo hidden = info.products().get(0);
            assertThat(hidden.name()).isEqualTo("상품100");
            assertThat(hidden.visible()).isFalse();
            assertThat(hidden.saleStatus()).isEqualTo(SaleStatus.STOP_SELLING);
            LookProductAdminInfo missing = info.products().get(1);
            assertThat(missing.name()).isNull();
            assertThat(missing.visible()).isFalse();
            assertThat(missing.saleStatus()).isNull();
        }
    }

    private LookProcessor processor(String base) {
        return new LookProcessor(port, new SnowflakeIdGenerator(1, 1), MediaUrlPolicy.of(base));
    }

    private static LookUpsertCommand command(String url, Long... productIds) {
        return LookUpsertCommand.builder()
            .title("가을 룩")
            .image(LookUpsertCommand.Image.builder().url(url).width(1200).height(1600).altText("대체").build())
            .visible(true)
            .productIds(List.of(productIds))
            .build();
    }

    private static Look look(long id, int displayOrder) {
        return new Look(id, "룩" + id, null, new LookImage(IMAGE_URL, null, null, null), true, displayOrder);
    }

    private static void assertError(ThrowingCallable call, LookErrorCode errorCode) {
        assertThatThrownBy(call)
            .isInstanceOf(LookException.class)
            .satisfies(exception -> assertThat(((LookException) exception).getErrorCode()).isEqualTo(errorCode));
    }
}
