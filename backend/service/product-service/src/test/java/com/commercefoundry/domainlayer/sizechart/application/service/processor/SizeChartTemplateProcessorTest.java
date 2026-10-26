package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartTemplateUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import com.commercefoundry.domainlayer.sizechart.application.port.out.ProductSizeChartRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartTemplateRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplate;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplateColumn;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 상품 표가 템플릿을 쓰는 동안에는 단위와 항목 집합을 못 바꾸고, 아무도 안 쓰면 항목을 새로 발급한다.
 */
@ExtendWith(MockitoExtension.class)
class SizeChartTemplateProcessorTest {

    private static final long TEMPLATE_ID = 10L;

    @Mock
    private SizeChartTemplateRepositoryPort sizeChartTemplateRepositoryPort;

    @Mock
    private ProductSizeChartRepositoryPort productSizeChartRepositoryPort;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private SizeChartTemplateProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SizeChartTemplateProcessor(sizeChartTemplateRepositoryPort, productSizeChartRepositoryPort, snowflakeIdGenerator);
    }

    @Test
    @DisplayName("쓰는 중이면 단위를 바꾸지 못한다 — PRODUCT_062")
    void rejectsBaseUnitChangeWhileInUse() {
        lockInUse(SizeChartUnit.CM);

        assertThatThrownBy(() -> processor.update(TEMPLATE_ID, command(SizeChartUnit.INCH, column(1L, "가슴", 0), column(2L, "허리", 1))))
            .isInstanceOf(SizeChartException.class)
            .extracting(exception -> ((SizeChartException) exception).getErrorCode())
            .isEqualTo(SizeChartErrorCode.TEMPLATE_STRUCTURE_LOCKED);
        verify(sizeChartTemplateRepositoryPort, never()).updateColumns(any(), any());
    }

    @Test
    @DisplayName("쓰는 중이면 항목을 빼거나 새 항목을 넣지 못한다 — PRODUCT_062")
    void rejectsColumnSetChangeWhileInUse() {
        lockInUse(SizeChartUnit.CM);

        assertThatThrownBy(() -> processor.update(TEMPLATE_ID, command(SizeChartUnit.CM, column(1L, "가슴", 0))))
            .isInstanceOf(SizeChartException.class)
            .extracting(exception -> ((SizeChartException) exception).getErrorCode())
            .isEqualTo(SizeChartErrorCode.TEMPLATE_STRUCTURE_LOCKED);
        assertThatThrownBy(() -> processor.update(TEMPLATE_ID, command(SizeChartUnit.CM, column(1L, "가슴", 0), column(null, "소매", 2))))
            .isInstanceOf(SizeChartException.class)
            .extracting(exception -> ((SizeChartException) exception).getErrorCode())
            .isEqualTo(SizeChartErrorCode.TEMPLATE_STRUCTURE_LOCKED);
    }

    @Test
    @DisplayName("쓰는 중이면 같은 항목 아이디를 두 번 보내지 못한다 — PRODUCT_071")
    void rejectsDuplicateColumnIdWhileInUse() {
        lockInUse(SizeChartUnit.CM);

        assertThatThrownBy(() -> processor.update(TEMPLATE_ID, command(SizeChartUnit.CM, column(1L, "가슴", 0), column(1L, "허리", 1))))
            .isInstanceOf(SizeChartException.class)
            .extracting(exception -> ((SizeChartException) exception).getErrorCode())
            .isEqualTo(SizeChartErrorCode.COLUMN_ID_DUPLICATED);
    }

    @Test
    @DisplayName("쓰는 중이면 같은 항목 아이디의 이름 · 설명 · 순서만 바꾼다")
    void keepsColumnIdsWhileInUse() {
        lockInUse(SizeChartUnit.CM);

        processor.update(TEMPLATE_ID, command(SizeChartUnit.CM, column(2L, "허리둘레", 0), column(1L, "가슴둘레", 1)));

        verify(sizeChartTemplateRepositoryPort).updateColumns(eq(new SizeChartTemplate(TEMPLATE_ID, "한복", SizeChartUnit.CM)), any());
        verify(sizeChartTemplateRepositoryPort, never()).replaceColumns(any(), any());
        verify(snowflakeIdGenerator, never()).generateId();
    }

    @Test
    @DisplayName("아무도 안 쓰면 항목을 지우고 새 아이디로 다시 넣는다")
    void replacesColumnsWhenUnused() {
        when(sizeChartTemplateRepositoryPort.lockById(TEMPLATE_ID))
            .thenReturn(Optional.of(new SizeChartTemplate(TEMPLATE_ID, "옛 이름", SizeChartUnit.CM)));
        when(productSizeChartRepositoryPort.existsByTemplateId(TEMPLATE_ID)).thenReturn(false);
        when(snowflakeIdGenerator.generateId()).thenReturn(90L);

        processor.update(TEMPLATE_ID, command(SizeChartUnit.INCH, column(null, "chest", 0)));

        verify(sizeChartTemplateRepositoryPort).replaceColumns(eq(new SizeChartTemplate(TEMPLATE_ID, "한복", SizeChartUnit.INCH)), any());
        verify(sizeChartTemplateRepositoryPort, never()).updateColumns(any(), any());
        verify(snowflakeIdGenerator).generateId();
    }

    @Test
    @DisplayName("쓰는 중이면 템플릿을 지우지 못한다 — PRODUCT_063")
    void rejectsDeleteWhileInUse() {
        when(sizeChartTemplateRepositoryPort.lockById(TEMPLATE_ID))
            .thenReturn(Optional.of(new SizeChartTemplate(TEMPLATE_ID, "한복", SizeChartUnit.CM)));
        when(productSizeChartRepositoryPort.existsByTemplateId(TEMPLATE_ID)).thenReturn(true);

        assertThatThrownBy(() -> processor.delete(TEMPLATE_ID))
            .isInstanceOf(SizeChartException.class)
            .extracting(exception -> ((SizeChartException) exception).getErrorCode())
            .isEqualTo(SizeChartErrorCode.TEMPLATE_IN_USE);
        verify(sizeChartTemplateRepositoryPort, never()).delete(anyLong());
    }

    private void lockInUse(SizeChartUnit baseUnit) {
        when(sizeChartTemplateRepositoryPort.lockById(TEMPLATE_ID))
            .thenReturn(Optional.of(new SizeChartTemplate(TEMPLATE_ID, "한복", baseUnit)));
        when(productSizeChartRepositoryPort.existsByTemplateId(TEMPLATE_ID)).thenReturn(true);
        when(sizeChartTemplateRepositoryPort.findColumns(TEMPLATE_ID)).thenReturn(List.of(
            new SizeChartTemplateColumn(1L, TEMPLATE_ID, "가슴", null, 0),
            new SizeChartTemplateColumn(2L, TEMPLATE_ID, "허리", null, 1)
        ));
    }

    private static SizeChartTemplateUpsertCommand command(SizeChartUnit baseUnit, SizeChartTemplateUpsertCommand.Column... columns) {
        return new SizeChartTemplateUpsertCommand("한복", baseUnit, List.of(columns));
    }

    private static SizeChartTemplateUpsertCommand.Column column(Long columnId, String name, int displayOrder) {
        return new SizeChartTemplateUpsertCommand.Column(columnId, name, null, displayOrder);
    }
}
