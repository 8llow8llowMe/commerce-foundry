package com.commercefoundry.domainlayer.sizechart.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductLookupProcessor;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter.SizeChartAdminPresenter;
import com.commercefoundry.domainlayer.sizechart.application.command.ProductSizeChartUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartTemplateUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartTemplateDetailInfo;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.ProductSizeChartProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartTemplateProcessor;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 어드민 사이즈표 쓰기의 감사 호출 (#163) — 업무가 끝나면 성공, 업무 예외면 실패를 한 번 남기고 예외는 그대로 던진다.
 * 템플릿은 {@code size-chart-template:<id>}, 상품 사이즈표 교체는 경로의 상품 아이디가 대상이다.
 */
@ExtendWith(MockitoExtension.class)
class SizeChartAdminFacadeTest {

    private static final long ACTOR = 77L;
    private static final SizeChartTemplateUpsertCommand TEMPLATE = SizeChartTemplateUpsertCommand.builder().name("상의").baseUnit(SizeChartUnit.CM).build();
    private static final ProductSizeChartUpsertCommand CHART = ProductSizeChartUpsertCommand.builder().templateId(1L).build();

    @Mock
    private SizeChartTemplateProcessor sizeChartTemplateProcessor;

    @Mock
    private ProductSizeChartProcessor productSizeChartProcessor;

    @Mock
    private ProductLookupProcessor productLookupProcessor;

    @Mock
    private SizeChartAdminPresenter sizeChartAdminPresenter;

    @Mock
    private ProductAdminAuditProcessor productAdminAuditProcessor;

    @InjectMocks
    private SizeChartAdminFacade facade;

    @Test
    @DisplayName("템플릿 생성 성공은 만든 아이디로, 실패(항목 이름 중복)는 아이디 없이 남기고 예외를 그대로 던진다")
    void createTemplate() {
        given(sizeChartTemplateProcessor.create(TEMPLATE)).willReturn(new SizeChartTemplateDetailInfo(7L, "상의", SizeChartUnit.CM, List.of()));

        facade.createTemplate(TEMPLATE, ACTOR);

        verify(productAdminAuditProcessor).sizeChartTemplateCreated(ACTOR, 7L, true);

        SizeChartException duplicated = new SizeChartException(SizeChartErrorCode.COLUMN_NAME_DUPLICATED);
        given(sizeChartTemplateProcessor.create(TEMPLATE)).willThrow(duplicated);

        assertThatThrownBy(() -> facade.createTemplate(TEMPLATE, ACTOR)).isSameAs(duplicated);

        verify(productAdminAuditProcessor).sizeChartTemplateCreated(ACTOR, null, false);
    }

    @Test
    @DisplayName("템플릿 수정은 경로 아이디로 성공 · 실패(없음 404)를 남긴다")
    void updateTemplate() {
        given(sizeChartTemplateProcessor.update(9L, TEMPLATE)).willReturn(new SizeChartTemplateDetailInfo(9L, "상의", SizeChartUnit.CM, List.of()));
        SizeChartException notFound = new SizeChartException(SizeChartErrorCode.NOT_FOUND_TEMPLATE);
        given(sizeChartTemplateProcessor.update(10L, TEMPLATE)).willThrow(notFound);

        facade.updateTemplate(9L, TEMPLATE, ACTOR);
        assertThatThrownBy(() -> facade.updateTemplate(10L, TEMPLATE, ACTOR)).isSameAs(notFound);

        verify(productAdminAuditProcessor).sizeChartTemplateUpdated(ACTOR, 9L, true);
        verify(productAdminAuditProcessor).sizeChartTemplateUpdated(ACTOR, 10L, false);
        verify(productAdminAuditProcessor, never()).sizeChartTemplateUpdated(anyLong(), eq(10L), eq(true));
    }

    @Test
    @DisplayName("템플릿 삭제는 경로 아이디로 성공 · 실패(쓰는 중 409)를 남긴다")
    void deleteTemplate() {
        SizeChartException inUse = new SizeChartException(SizeChartErrorCode.TEMPLATE_IN_USE);

        facade.deleteTemplate(9L, ACTOR);
        willThrow(inUse).given(sizeChartTemplateProcessor).delete(10L);
        assertThatThrownBy(() -> facade.deleteTemplate(10L, ACTOR)).isSameAs(inUse);

        verify(productAdminAuditProcessor).sizeChartTemplateDeleted(ACTOR, 9L, true);
        verify(productAdminAuditProcessor).sizeChartTemplateDeleted(ACTOR, 10L, false);
    }

    @Test
    @DisplayName("상품 사이즈표 교체는 경로 상품 아이디로 성공 · 실패(상품 없음 404)를 남긴다")
    void replaceChart() {
        SizeChartException productMissing = new SizeChartException(SizeChartErrorCode.PRODUCT_NOT_FOUND);

        facade.replaceProductSizeChart(700L, CHART, ACTOR);
        willThrow(productMissing).given(productSizeChartProcessor).replace(eq(701L), any(), eq(CHART));
        assertThatThrownBy(() -> facade.replaceProductSizeChart(701L, CHART, ACTOR)).isSameAs(productMissing);

        verify(productAdminAuditProcessor).productSizeChartReplaced(ACTOR, 700L, true);
        verify(productAdminAuditProcessor).productSizeChartReplaced(ACTOR, 701L, false);
        verify(productAdminAuditProcessor, never()).productSizeChartReplaced(anyLong(), eq(701L), eq(true));
    }
}
