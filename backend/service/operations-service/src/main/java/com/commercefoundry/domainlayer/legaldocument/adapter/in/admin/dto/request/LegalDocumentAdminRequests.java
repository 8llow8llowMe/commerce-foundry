package com.commercefoundry.domainlayer.legaldocument.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.legaldocument.application.command.LegalDocumentCommands;
import com.commercefoundry.domainlayer.legaldocument.application.exception.LegalDocumentValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class LegalDocumentAdminRequests {
    private LegalDocumentAdminRequests() {
    }

    public record Draft(
        @Schema(description = "최초 생성은 null, 수정은 조회한 문서 버전")
        @PositiveOrZero(message = LegalDocumentValidationMessage.VERSION)
        Long expectedVersion,

        @Schema(description = "제목 (최대 200자, 단순 텍스트)")
        @NotBlank(message = LegalDocumentValidationMessage.TITLE)
        @Size(max = 200, message = LegalDocumentValidationMessage.TITLE)
        @Pattern(regexp = "[^\\p{Cc}]*", message = LegalDocumentValidationMessage.TITLE)
        String title,

        @Schema(description = "본문 (최대 50000자, HTML을 실행하지 않는 단순 텍스트)")
        @NotBlank(message = LegalDocumentValidationMessage.BODY)
        @Size(max = 50000, message = LegalDocumentValidationMessage.BODY)
        @Pattern(regexp = "[\\P{Cc}\\r\\n\\t]*", message = LegalDocumentValidationMessage.BODY)
        String body
    ) {
        public LegalDocumentCommands.SaveDraft toCommand(String type, String locale) {
            return new LegalDocumentCommands.SaveDraft(type, locale, expectedVersion, title, body);
        }
    }

    public record Publish(
        @Schema(description = "조회한 문서 버전 (현재 게시본 재요청은 버전이 오래되어도 변경 없이 성공)")
        @NotNull(message = LegalDocumentValidationMessage.VERSION)
        @PositiveOrZero(message = LegalDocumentValidationMessage.VERSION)
        Long expectedVersion,

        @Schema(description = "게시할 최신 리비전 번호")
        @Positive(message = LegalDocumentValidationMessage.REVISION)
        int revisionNumber,

        @Schema(description = "문서 내용을 검토했는지 명시적으로 확인 (true 필수)")
        @NotNull(message = LegalDocumentValidationMessage.REVIEW)
        @AssertTrue(message = LegalDocumentValidationMessage.REVIEW)
        Boolean reviewConfirmed
    ) {
        public LegalDocumentCommands.Publish toCommand(String type, String locale) {
            return new LegalDocumentCommands.Publish(type, locale, expectedVersion, revisionNumber, Boolean.TRUE.equals(reviewConfirmed));
        }
    }
}
