package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftAcceptedResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftApplyResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftResponse.Candidate;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftResponse.Cell;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftResponse.Column;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftResponse.Metadata;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftResponse.Row;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftResponse.Template;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftResponse.Warning;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftSummaryResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartImageInspectionResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartImageSourceResponse;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartImageInspectionInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartImageSourceInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftApplyInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftSummaryInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftUnavailableException;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import com.commercefoundry.persistence.dto.PageResponse;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 저장된 JSON은 허용한 타입으로만 읽는다. JSON 트리나 원문을 응답에 싣지 않는다. */
@Component
public class SizeChartDraftAdminPresenter {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(12000).build()).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public SizeChartDraftAcceptedResponse accepted(SizeChartDraftInfo info) {
        if (info.jobId() == null || info.jobId() <= 0) {
            throw new SizeChartDraftUnavailableException(info.draftId(), SizeChartDraftTransportErrorCode.REGISTRATION_UNAVAILABLE);
        }
        return new SizeChartDraftAcceptedResponse(id(info.draftId()), id(info.jobId()));
    }

    public SizeChartDraftResponse detail(SizeChartDraftInfo info) {
        try {
            var snapshot = JSON.readValue(info.templateJson(), Snapshot.class);
            var template = new Template(id(info.templateId()), info.templateRevision(), unit(snapshot.unit()),
                snapshot.columns().stream().map(c -> new Column(id(c.id()), c.name())).toList());
            Candidate candidate = null;
            if (info.candidateJson() != null) {
                var data = JSON.readValue(info.candidateJson(), CandidateData.class);
                candidate = new Candidate(unit(data.unit()), data.rows().stream()
                    .map(r -> new Row(r.sizeLabel(), r.cells().stream().map(c -> new Cell(id(c.columnId()), c.value())).toList())).toList(),
                    data.warnings().stream().map(this::warning).toList());
            }
            return new SizeChartDraftResponse(id(info.draftId()), id(info.productId()), id(info.jobId()), status(info.status()),
                info.version(), info.expiresAt(), template, candidate);
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            // 외부/저장 JSON과 역직렬화 진단은 운영자 응답과 예외 원인에 남기지 않는다.
            throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
    }

    public PageResponse<SizeChartDraftSummaryResponse> page(Page<SizeChartDraftSummaryInfo> infos) {
        return PageResponse.of(infos.map(info -> new SizeChartDraftSummaryResponse(id(info.draftId()), id(info.productId()),
            id(info.templateId()), id(info.jobId()), status(info.status()), info.version(), info.expiresAt())));
    }

    public SizeChartDraftApplyResponse applied(SizeChartDraftApplyInfo info) {
        return new SizeChartDraftApplyResponse(id(info.draftId()), id(info.productId()), id(info.chartId()), info.chartRevision(), info.appliedAt());
    }

    public List<SizeChartImageSourceResponse> imageSources(List<SizeChartImageSourceInfo> infos) {
        return infos.stream().map(info -> new SizeChartImageSourceResponse(info.sourceId(), info.previewUrl(), info.eligible(),
            info.rejection() == null ? null : info.rejection().toMetadata()))
            .toList();
    }

    public SizeChartImageInspectionResponse inspection(SizeChartImageInspectionInfo info) {
        return new SizeChartImageInspectionResponse(info.sourceId(), info.contentHash(), info.contentType(), info.contentLength());
    }

    private Warning warning(WarningData warning) {
        String notice = switch (warning.code()) {
            case "SIZE_ORDER" -> "사이즈 순서를 확인해 주세요.";
            case "MISSING_CELL" -> "빠진 치수를 입력해 주세요.";
            case "MEASUREMENT_OUTLIER" -> "다른 치수와 차이가 큽니다. 측정값을 확인해 주세요.";
            case "MEASUREMENT_ORDER" -> "사이즈별 치수 순서를 확인해 주세요.";
            default -> throw new IllegalArgumentException();
        };
        return new Warning(warning.code() + ":" + warning.rowIndex() + ":" + warning.columnId(),
            new Metadata(warning.code(), notice, notice), warning.rowIndex(), warning.columnId() == 0 ? null : id(warning.columnId()));
    }

    private Metadata status(SizeChartDraftStatus value) {
        String name = switch (value) {
            case REQUESTED -> "생성 중";
            case READY -> "검토 대기";
            case FAILED -> "생성 실패";
            case CANCELLED -> "생성 취소";
            case APPLIED -> "적용 완료";
        };
        String notice = switch (value) {
            case REQUESTED -> "생성 작업을 확인한 뒤 새로고침해 주세요.";
            case READY -> "치수와 경고를 검토한 뒤 적용해 주세요.";
            case FAILED -> "입력한 치수를 확인한 뒤 새 초안을 요청해 주세요.";
            case CANCELLED -> "생성 작업이 취소되었습니다. 필요하면 새 초안을 요청해 주세요.";
            case APPLIED -> "상품 사이즈표에 적용했습니다.";
        };
        return new Metadata(value.name(), name, notice);
    }

    private Metadata unit(String value) {
        return switch (value) {
            case "CM" -> new Metadata("CM", "센티미터", "센티미터 단위 치수");
            case "INCH" -> new Metadata("INCH", "인치", "인치 단위 치수");
            default -> throw new IllegalArgumentException();
        };
    }

    private static String id(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException();
        }
        return Long.toString(value);
    }

    private static String id(Long value) {
        return value == null ? null : id(value.longValue());
    }

    private record Snapshot(String unit, List<ColumnData> columns) {
    }

    private record ColumnData(long id, String name) {
    }

    private record CandidateData(String unit, List<RowData> rows, List<WarningData> warnings) {
    }

    private record RowData(String sizeLabel, List<CellData> cells) {
    }

    private record CellData(long columnId, BigDecimal value) {
    }

    private record WarningData(String code, int rowIndex, long columnId) {
    }
}
