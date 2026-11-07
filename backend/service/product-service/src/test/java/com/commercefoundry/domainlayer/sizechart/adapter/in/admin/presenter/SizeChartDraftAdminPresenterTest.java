package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftApplyInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftSummaryInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftUnavailableException;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

class SizeChartDraftAdminPresenterTest {
    private static final long ID = 4_611_686_018_427_387_904L;
    private static final Instant EXPIRES = Instant.parse("2026-11-10T00:00:00Z");
    private final SizeChartDraftAdminPresenter presenter = new SizeChartDraftAdminPresenter();

    @Test
    void structuredCandidatePreservesMissingCellsAndStringifiesEveryIdentifier() {
        var response = presenter.detail(info(ID, """
            {"unit":"CM","rows":[{"sizeLabel":"S","cells":[{"columnId":4611686018427387904,"value":null}]}],
             "warnings":[{"code":"MISSING_CELL","rowIndex":0,"columnId":4611686018427387904},
                         {"code":"SIZE_ORDER","rowIndex":0,"columnId":0}]}
            """));

        assertThat(response.draftId()).isEqualTo(Long.toString(ID));
        assertThat(response.productId()).isEqualTo(Long.toString(ID));
        assertThat(response.jobId()).isEqualTo(Long.toString(ID));
        assertThat(response.template().templateId()).isEqualTo(Long.toString(ID));
        assertThat(response.template().columns().getFirst().columnId()).isEqualTo(Long.toString(ID));
        assertThat(response.candidate().rows().getFirst().cells().getFirst().columnId()).isEqualTo(Long.toString(ID));
        assertThat(response.candidate().rows().getFirst().cells().getFirst().value()).isNull();
        assertThat(response.candidate().warnings().getFirst().warningId()).isEqualTo("MISSING_CELL:0:" + ID);
        assertThat(response.candidate().warnings().getFirst().columnId()).isEqualTo(Long.toString(ID));
        assertThat(response.candidate().warnings().getFirst().type().description()).isEqualTo("빠진 치수를 입력해 주세요.");
        assertThat(response.candidate().warnings().get(1).columnId()).isNull();
        assertThat(JsonMapper.builder().build().writeValueAsString(response))
            .doesNotContain("templateJson", "candidateJson", "requestedByMemberId", "sourceText", "requestHash", "baseChartId");
    }

    @Test
    void decimalMeasurementsAreNumbersAndAppliedReceiptOmitsActor() {
        var detail = presenter.detail(info(ID, """
            {"unit":"CM","rows":[{"sizeLabel":"S","cells":[{"columnId":4611686018427387904,"value":50.25}]}],"warnings":[]}
            """));
        assertThat(detail.candidate().rows().getFirst().cells().getFirst().value()).isEqualByComparingTo(new BigDecimal("50.25"));
        var receipt = presenter.applied(new SizeChartDraftApplyInfo(ID, ID, ID, 3, 900, EXPIRES));
        assertThat(receipt.chartId()).isEqualTo(Long.toString(ID));
        assertThat(JsonMapper.builder().build().writeValueAsString(receipt)).doesNotContain("appliedByMemberId");
    }

    @Test
    void acceptanceRequiresBoundJobAndContainsOnlyTwoIdentifiers() {
        assertThat(presenter.accepted(info(ID, null)).jobId()).isEqualTo(Long.toString(ID));
        assertThatThrownBy(() -> presenter.accepted(info(null, null))).isInstanceOf(SizeChartDraftUnavailableException.class);
        assertThatThrownBy(() -> presenter.accepted(info(0L, null))).isInstanceOf(SizeChartDraftUnavailableException.class);
        assertThat(JsonMapper.builder().build().valueToTree(presenter.accepted(info(ID, null))).size()).isEqualTo(2);
    }

    @Test
    void paginationRetainsDatabaseTotalAndOmitsRequester() {
        var summary = new SizeChartDraftSummaryInfo(ID, ID, ID, 999, null, SizeChartDraftStatus.REQUESTED, 0, EXPIRES);
        var page = presenter.page(new PageImpl<>(List.of(summary), PageRequest.of(2, 10), 51));
        assertThat(page.totalElements()).isEqualTo(51);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(6);
        assertThat(page.contents().getFirst().draftId()).isEqualTo(Long.toString(ID));
        assertThat(JsonMapper.builder().build().writeValueAsString(page)).doesNotContain("requestedByMemberId");
    }

    @Test
    void unexpectedSnapshotFieldsAndMalformedCandidateFailWithoutRetainingRawBody() {
        assertThatThrownBy(() -> presenter.detail(info(ID, "{\"providerSecret\":\"private-provider-body\"}")))
            .isInstanceOf(SizeChartDraftTransportException.class).hasNoCause().hasMessageNotContaining("private-provider-body");
    }

    private SizeChartDraftInfo info(Long jobId, String candidate) {
        return new SizeChartDraftInfo(ID, ID, ID, 1,
            "{\"unit\":\"CM\",\"columns\":[{\"id\":4611686018427387904,\"name\":\"가슴\"}]}",
            null, null, jobId, SizeChartDraftStatus.READY, 2, EXPIRES, candidate);
    }
}
