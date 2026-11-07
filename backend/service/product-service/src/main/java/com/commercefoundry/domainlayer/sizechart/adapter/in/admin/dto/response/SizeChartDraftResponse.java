package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "검토용 사이즈표 초안. 원문과 요청자 정보는 포함하지 않습니다")
public record SizeChartDraftResponse(
    @Schema(description = "초안 아이디")
    String draftId,

    @Schema(description = "상품 아이디")
    String productId,

    @Schema(description = "생성 작업 아이디. 연결 전에는 null")
    String jobId,

    @Schema(description = "초안 상태")
    Metadata status,

    @Schema(description = "검토한 초안 버전. 적용 요청에 전달합니다")
    long version,

    @Schema(description = "초안 만료 시각")
    Instant expiresAt,

    @Schema(description = "초안 생성 당시 템플릿")
    Template template,

    @Schema(description = "검토할 후보. 생성 전에는 null")
    Candidate candidate
) {
    @Schema(description = "운영자 표시 정보")
    public record Metadata(
        @Schema(description = "분류 코드")
        String code,

        @Schema(description = "표시 이름")
        String name,

        @Schema(description = "운영자 안내")
        String description
    ) {
    }

    @Schema(description = "템플릿 스냅숏")
    public record Template(
        @Schema(description = "템플릿 아이디")
        String templateId,

        @Schema(description = "캡처한 템플릿 버전")
        long revision,

        @Schema(description = "치수 단위")
        Metadata unit,

        @Schema(description = "템플릿 항목")
        List<Column> columns
    ) {
    }

    @Schema(description = "템플릿 항목")
    public record Column(
        @Schema(description = "템플릿 항목 아이디")
        String columnId,

        @Schema(description = "항목 이름")
        String name
    ) {
    }

    @Schema(description = "생성된 후보 사이즈표")
    public record Candidate(
        @Schema(description = "치수 단위")
        Metadata unit,

        @Schema(description = "사이즈 행")
        List<Row> rows,

        @Schema(description = "검토할 경고")
        List<Warning> warnings
    ) {
    }

    @Schema(description = "후보 사이즈 행")
    public record Row(
        @Schema(description = "사이즈 이름")
        String sizeLabel,

        @Schema(description = "치수 칸. 누락 치수는 null이며 0으로 채우지 않습니다")
        List<Cell> cells
    ) {
    }

    @Schema(description = "후보 치수 칸")
    public record Cell(
        @Schema(description = "템플릿 항목 아이디")
        String columnId,

        @Schema(description = "치수. 확인이 필요한 누락 칸은 null")
        BigDecimal value
    ) {
    }

    @Schema(description = "운영자가 확인할 경고")
    public record Warning(
        @Schema(description = "서버 발급 경고 아이디. 적용 요청의 확인 목록에 사용합니다")
        String warningId,

        @Schema(description = "경고 종류와 안내")
        Metadata type,

        @Schema(description = "0부터 센 후보 행 위치")
        int rowIndex,

        @Schema(description = "템플릿 항목 아이디. 행 전체 경고는 null")
        String columnId
    ) {
    }
}
