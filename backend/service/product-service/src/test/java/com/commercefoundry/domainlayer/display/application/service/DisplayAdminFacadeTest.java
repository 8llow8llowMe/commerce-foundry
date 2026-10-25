package com.commercefoundry.domainlayer.display.application.service;

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

import com.commercefoundry.domainlayer.display.adapter.in.admin.presenter.DisplayAdminPresenter;
import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import com.commercefoundry.domainlayer.display.application.exception.DisplayErrorCode;
import com.commercefoundry.domainlayer.display.application.exception.DisplayException;
import com.commercefoundry.domainlayer.display.application.info.DisplaySectionAdminInfo;
import com.commercefoundry.domainlayer.display.application.service.processor.DisplaySectionProcessor;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItem;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplayLiveStatus;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySection;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionContent;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
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
 * 어드민 전시 쓰기의 흐름 — 저장소 없이 보는 판정이 잠금보다 먼저, 상품 확인은 잠금 안에서 한 번, 잠금 실패 · 시간 초과는 409 · PRODUCT_058.
 */
class DisplayAdminFacadeTest {

    private static final DisplaySectionUpsertCommand COMMAND = DisplaySectionUpsertCommand.builder()
        .sectionType(DisplaySectionType.PRODUCT_PICK).visible(true)
        .items(List.of(DisplaySectionUpsertCommand.Item.builder().itemType(DisplayItemType.PRODUCT).productId(7L).build()))
        .build();
    private static final DisplaySectionContent DRAFT = new DisplaySectionContent(
        new DisplaySection(0L, DisplaySectionType.PRODUCT_PICK, null, null, null, null, null, 0, true, null, null),
        List.of(new DisplayItem(0L, 0L, 0, DisplayItemType.PRODUCT, null, 7L, null))
    );

    private static final DisplaySectionAdminInfo INFO = new DisplaySectionAdminInfo(
        1L, DisplaySectionType.PRODUCT_PICK, null, null, null, null, null, 0, true, null, null, DisplayLiveStatus.LIVE, List.of()
    );

    private final DisplaySectionProcessor processor = mock(DisplaySectionProcessor.class);
    private final ProductLookupProcessor productLookupProcessor = mock(ProductLookupProcessor.class);
    private final DisplayAdminFacade facade = new DisplayAdminFacade(processor, productLookupProcessor, new DisplayAdminPresenter());

    DisplayAdminFacadeTest() {
        given(processor.toAdminInfo(any(), any())).willReturn(INFO);
    }

    @Test
    @DisplayName("생성 — 초안 판정 → 잠금 → 상한 → 상품 확인(한 번) → 저장 순서다")
    void createFlow() {
        given(processor.draftOf(COMMAND)).willReturn(DRAFT);
        given(processor.lockAll()).willReturn(List.of());
        given(productLookupProcessor.findAllIncludingHidden(anyCollection())).willReturn(Map.of());
        given(processor.create(any(), any())).willReturn(DRAFT);

        facade.createSection(COMMAND);

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
        given(processor.draftOf(COMMAND)).willThrow(new DisplayException(DisplayErrorCode.DISPLAY_PRODUCT_DUPLICATED));

        assertThatThrownBy(() -> facade.createSection(COMMAND)).isInstanceOf(DisplayException.class);
        verify(processor, never()).lockAll();
    }

    @Test
    @DisplayName("잠금 대기 시간 초과 · 트랜잭션 시간 초과 · 잠금 획득 실패는 409 · PRODUCT_058 이다")
    void lockFailuresBecomeBusy() {
        for (RuntimeException failure : List.of(
            new QueryTimeoutException("timeout"), new TransactionTimedOutException("timeout"), new CannotAcquireLockException("lock")
        )) {
            willThrow(failure).given(processor).lockAll();

            assertThatThrownBy(() -> facade.deleteSection(1L))
                .isInstanceOf(DisplayException.class)
                .satisfies(exception -> assertThat(((DisplayException) exception).getErrorCode()).isEqualTo(DisplayErrorCode.DISPLAY_BUSY))
                .hasCause(failure);
        }
    }

    @Test
    @DisplayName("수정 · 삭제는 잠근 목록에서 대상을 확인한 뒤 쓴다")
    void updateAndDeleteRequireSection() {
        DisplaySection current = new DisplaySection(5L, DisplaySectionType.PRODUCT_PICK, null, null, null, null, null, 2, true, null, null);
        given(processor.draftOf(COMMAND)).willReturn(DRAFT);
        given(processor.lockAll()).willReturn(List.of(current));
        given(processor.requireSection(List.of(current), 5L)).willReturn(current);
        given(productLookupProcessor.findAllIncludingHidden(anyCollection())).willReturn(Map.of());
        given(processor.replace(current, DRAFT)).willReturn(DRAFT);

        facade.updateSection(5L, COMMAND);
        facade.deleteSection(5L);

        verify(processor).replace(current, DRAFT);
        verify(processor).delete(5L);
    }
}
