package com.commercefoundry.domainlayer.look.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.look.adapter.in.admin.presenter.LookAdminPresenter;
import com.commercefoundry.domainlayer.look.application.command.LookUpsertCommand;
import com.commercefoundry.domainlayer.look.application.exception.LookErrorCode;
import com.commercefoundry.domainlayer.look.application.exception.LookException;
import com.commercefoundry.domainlayer.look.application.info.LookAdminInfo;
import com.commercefoundry.domainlayer.look.application.service.processor.LookProcessor;
import com.commercefoundry.domainlayer.look.domain.model.Look;
import com.commercefoundry.domainlayer.look.domain.model.LookContent;
import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import com.commercefoundry.domainlayer.look.domain.model.LookItem;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductLookupProcessor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * 어드민 룩 쓰기의 흐름 — 저장소 없이 보는 판정이 잠금보다 먼저, 상품 확인은 잠금 안에서 한 번, 잠금 실패 · 시간 초과는 409 · LOOK_008.
 */
class LookAdminFacadeTest {

    private static final LookImage IMAGE = new LookImage("https://cdn.example.com/media/a.jpg", null, null, null);
    private static final LookUpsertCommand COMMAND = LookUpsertCommand.builder()
        .title("가을 룩").image(LookUpsertCommand.Image.builder().url(IMAGE.url()).build()).visible(true).productIds(List.of(7L))
        .build();
    private static final LookContent DRAFT = new LookContent(new Look(0L, "가을 룩", null, IMAGE, true, 0), List.of(new LookItem(0L, 0L, 7L, 0)));
    private static final LookAdminInfo INFO = new LookAdminInfo(1L, "가을 룩", null, IMAGE, true, List.of());

    private final LookProcessor processor = mock(LookProcessor.class);
    private final ProductLookupProcessor productLookupProcessor = mock(ProductLookupProcessor.class);
    private final LookAdminFacade facade = new LookAdminFacade(processor, productLookupProcessor, new LookAdminPresenter());

    LookAdminFacadeTest() {
        given(processor.toAdminInfo(any(), any())).willReturn(INFO);
    }

    @Test
    @DisplayName("생성 — 초안 판정 → 잠금 → 상한 → 상품 확인(한 번) → 저장 순서다")
    void createFlow() {
        given(processor.draftOf(COMMAND)).willReturn(DRAFT);
        given(processor.lockAll()).willReturn(List.of());
        given(productLookupProcessor.findAllIncludingHidden(anyCollection())).willReturn(Map.of());
        given(processor.create(any(), any())).willReturn(DRAFT);

        assertThat(facade.create(COMMAND).lookId()).isEqualTo("1");

        InOrder order = inOrder(processor, productLookupProcessor);
        order.verify(processor).draftOf(COMMAND);
        order.verify(processor).lockAll();
        order.verify(processor).validateCanCreate(List.of());
        order.verify(productLookupProcessor).findAllIncludingHidden(DRAFT.productIds());
        order.verify(processor).validateProductsExist(DRAFT.productIds(), Map.of());
        order.verify(processor).create(DRAFT, List.of());
    }

    @Test
    @DisplayName("초안 판정에서 걸리면 잠그지 않는다")
    void invalidDraftDoesNotLock() {
        given(processor.draftOf(COMMAND)).willThrow(new LookException(LookErrorCode.LOOK_PRODUCT_DUPLICATED));

        assertThatThrownBy(() -> facade.create(COMMAND)).isInstanceOf(LookException.class);
        verify(processor, never()).lockAll();
    }

    @Test
    @DisplayName("잠금 대기 시간 초과 · 트랜잭션 시간 초과 · 잠금 획득 실패는 409 · LOOK_008 이다")
    void lockFailuresBecomeBusy() {
        for (RuntimeException failure : List.of(
            new QueryTimeoutException("timeout"), new TransactionTimedOutException("timeout"), new CannotAcquireLockException("lock")
        )) {
            willThrow(failure).given(processor).lockAll();

            assertThatThrownBy(() -> facade.delete(1L))
                .isInstanceOf(LookException.class)
                .satisfies(exception -> assertThat(((LookException) exception).getErrorCode()).isEqualTo(LookErrorCode.LOOK_BUSY))
                .hasCause(failure);
        }
    }

    @Test
    @DisplayName("수정 · 삭제는 잠근 목록에서 대상을 확인한 뒤 쓴다. 순서 변경은 잠근 목록으로 판정하고 새 전체 목록을 돌려준다")
    void updateDeleteReorder() {
        Look current = new Look(5L, "룩", null, IMAGE, true, 2);
        given(processor.draftOf(COMMAND)).willReturn(DRAFT);
        given(processor.lockAll()).willReturn(List.of(current));
        given(processor.requireLook(List.of(current), 5L)).willReturn(current);
        given(productLookupProcessor.findAllIncludingHidden(anyCollection())).willReturn(Map.of());
        given(processor.replace(current, DRAFT)).willReturn(DRAFT);
        given(processor.getAllContents()).willReturn(List.of());
        given(processor.toAdminInfos(any(), any())).willReturn(List.of(INFO));

        facade.update(5L, COMMAND);
        facade.delete(5L);
        assertThat(facade.reorder(List.of(5L)).looks()).hasSize(1);

        verify(processor).replace(current, DRAFT);
        verify(processor).delete(5L);
        verify(processor).reorder(List.of(current), List.of(5L));
    }
}
