package com.commercefoundry.domainlayer.knowledge.adapter.in.admin.dto.request;

import static com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeValidationMessage.*;

import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeContentCommand;
import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeCreateCommand;
import com.commercefoundry.domainlayer.knowledge.application.model.KnowledgeAdminQuery;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeCategory;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

public final class KnowledgeAdminRequests {

    private KnowledgeAdminRequests() {
    }

    @Getter
    @Setter
    public static class ListQuery {

        @Schema(description = "문서 유형 필터")
        private KnowledgeType type;

        @Schema(description = "언어 필터")
        private KnowledgeLocale locale;

        @Schema(description = "0부터 시작하는 페이지", defaultValue = "0")
        @Min(value = 0, message = PAGE)
        private int page;

        @Schema(description = "페이지 크기 (1~100)", defaultValue = "20")
        @Min(value = 1, message = SIZE)
        @Max(value = 100, message = SIZE)
        private int size = 20;

        public KnowledgeAdminQuery toQuery() {
            return new KnowledgeAdminQuery(type, locale, page, size);
        }
    }

    public record Content(
        @Schema(description = "제목 (최대 200자)")
        @NotBlank(message = TITLE) @Size(max = 200, message = TITLE)
        String title,

        @Schema(description = "본문 (최대 8000자)")
        @NotBlank(message = BODY) @Size(max = 8000, message = BODY)
        String body,

        @Schema(description = "카테고리")
        @NotNull(message = CATEGORY)
        KnowledgeCategory category,

        @Schema(description = "유효 시작 시각 (포함)")
        @NotNull(message = VALID_FROM)
        Instant validFrom,

        @Schema(description = "유효 종료 시각 (제외, 생략 시 무기한)")
        Instant validTo
    ) {
        @AssertTrue(message = PERIOD)
        @Schema(hidden = true)
        public boolean isPeriodValid() {
            return validFrom == null || validTo == null || validTo.isAfter(validFrom);
        }

        public KnowledgeContentCommand toCommand() {
            return new KnowledgeContentCommand(title, body, category, validFrom, validTo);
        }

        @Override
        public String toString() {
            return "Content[redacted]";
        }
    }

    public record Create(
        @Schema(description = "문서 유형 (생성 후 변경 불가)")
        @NotNull(message = TYPE)
        KnowledgeType type,

        @Schema(description = "언어 (생성 후 변경 불가)")
        @NotNull(message = LOCALE)
        KnowledgeLocale locale,

        @Schema(description = "초안 내용")
        @NotNull(message = CONTENT)
        @Valid
        Content content
    ) {
        public KnowledgeCreateCommand toCommand() {
            return new KnowledgeCreateCommand(type, locale, content.toCommand());
        }
    }

    public record Edit(
        @Schema(description = "최신 초안 리비전 번호")
        @Positive(message = REVISION)
        int revisionNumber,

        @Schema(description = "조회한 리비전 버전")
        @NotNull(message = VERSION) @PositiveOrZero(message = VERSION)
        Long expectedVersion,

        @Schema(description = "교체할 전체 내용")
        @NotNull(message = CONTENT)
        @Valid
        Content content
    ) {
    }

    public record Draft(
        @Schema(description = "조회한 문서 버전")
        @NotNull(message = DOCUMENT_VERSION) @PositiveOrZero(message = DOCUMENT_VERSION)
        Long expectedDocumentVersion,

        @Schema(description = "새 초안 내용")
        @NotNull(message = CONTENT)
        @Valid
        Content content
    ) {
    }

    public record Approve(
        @Schema(description = "승인할 최신 초안 번호")
        @Positive(message = REVISION)
        int revisionNumber,

        @Schema(description = "조회한 리비전 버전")
        @NotNull(message = VERSION) @PositiveOrZero(message = VERSION)
        Long expectedVersion
    ) {
    }

    public record Publish(
        @Schema(description = "게시할 최신 승인본 번호")
        @Positive(message = REVISION)
        int revisionNumber,

        @Schema(description = "조회한 리비전 버전")
        @NotNull(message = VERSION) @PositiveOrZero(message = VERSION)
        Long expectedVersion,

        @Schema(description = "조회한 문서 버전")
        @NotNull(message = DOCUMENT_VERSION) @PositiveOrZero(message = DOCUMENT_VERSION)
        Long expectedDocumentVersion
    ) {
    }
}
