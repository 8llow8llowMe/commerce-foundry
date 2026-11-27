package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftBatchItemInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftUnavailableException;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

/** 일괄 항목 결과 판정 (#737) — 오류 코드는 단건 API 와 같은 체계, 재시도 판정은 매핑 전 원래 코드. */
class SizeChartDraftBatchProcessorTest {
    private final SizeChartDraftBatchProcessor processor = new SizeChartDraftBatchProcessor();
    private final SizeChartDraftCreateCommand command = new SizeChartDraftCreateCommand(10, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm");

    @Test
    void acceptedItemCarriesDraftAndJobWithoutError() {
        var info = new SizeChartDraftInfo(9, 10, 20, 3, "{}", null, null, 200L, SizeChartDraftStatus.REQUESTED, 1, Instant.now(), null);
        var item = processor.accepted(command, info);
        assertThat(item).isEqualTo(new SizeChartDraftBatchItemInfo(10, command.idempotencyKey(), 9L, 200L, null, false));
    }

    @Test
    void transportFailuresKeepTheirCodeAndOnlyUncertainRegistrationKeepsRecoveryDraft() {
        var uncertain = processor.failed(command, new SizeChartDraftUnavailableException(9, SizeChartDraftTransportErrorCode.REGISTRATION_UNAVAILABLE));
        assertThat(uncertain.draftId()).isEqualTo(9L);
        assertThat(uncertain.errorCode()).isEqualTo("SIZE_CHART_DRAFT_501");
        assertThat(uncertain.retryable()).isTrue();

        var stale = processor.failed(command, new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.TEXT_SELECTION_CHANGED));
        assertThat(stale.draftId()).isNull();
        assertThat(stale.errorCode()).isEqualTo("SIZE_CHART_DRAFT_017");
        assertThat(stale.retryable()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "SIZE_CHART_BUSY, SIZE_CHART_DRAFT_003, true",
        "CHART_CONFLICT, SIZE_CHART_DRAFT_003, true",
        "NOT_FOUND_TEMPLATE, SIZE_CHART_DRAFT_001, false",
        "PRODUCT_NOT_FOUND, SIZE_CHART_DRAFT_001, false",
        "TEMPLATE_STRUCTURE_LOCKED, SIZE_CHART_DRAFT_003, false",
        "COLUMN_NAME_DUPLICATED, SIZE_CHART_DRAFT_030, false"
    })
    void sizeChartFailuresUseSingleApiCodesButRetryOnOriginalCode(SizeChartErrorCode original, String mapped, boolean retryable) {
        var item = processor.failed(command, new SizeChartException(original));
        assertThat(item.errorCode()).isEqualTo(mapped).doesNotStartWith("PRODUCT_");
        assertThat(item.retryable()).isEqualTo(retryable);
        assertThat(item.draftId()).isNull();
        assertThat(item.jobId()).isNull();
    }

    @Test
    void unexpectedFailureIsIsolatedAsRetryable505WithoutRawMessage() {
        var item = processor.failed(command, new IllegalStateException("private remote diagnostics"));
        assertThat(item.errorCode()).isEqualTo("SIZE_CHART_DRAFT_505");
        assertThat(item.retryable()).isTrue();
        assertThat(item.toString()).doesNotContain("private");
    }

    @Test
    void businessFailureMappingIsTheSingleApiContract() {
        assertThat(SizeChartDraftTransportErrorCode.ofBusinessFailure(HttpStatus.NOT_FOUND)).isEqualTo(SizeChartDraftTransportErrorCode.NOT_FOUND);
        assertThat(SizeChartDraftTransportErrorCode.ofBusinessFailure(HttpStatus.CONFLICT)).isEqualTo(SizeChartDraftTransportErrorCode.BINDING_CONFLICT);
        assertThat(SizeChartDraftTransportErrorCode.ofBusinessFailure(HttpStatus.SERVICE_UNAVAILABLE))
            .isEqualTo(SizeChartDraftTransportErrorCode.RESULT_UNAVAILABLE);
        assertThat(SizeChartDraftTransportErrorCode.ofBusinessFailure(HttpStatus.BAD_REQUEST)).isEqualTo(SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
    }
}
