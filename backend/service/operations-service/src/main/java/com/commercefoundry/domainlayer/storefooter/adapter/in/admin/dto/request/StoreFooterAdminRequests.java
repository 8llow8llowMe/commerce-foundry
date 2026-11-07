package com.commercefoundry.domainlayer.storefooter.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.storefooter.application.command.StoreFooterCommands;
import com.commercefoundry.domainlayer.storefooter.application.exception.StoreFooterValidationMessage;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class StoreFooterAdminRequests {
    private StoreFooterAdminRequests() {
    }

    public record CopyrightText(
        @Schema(description = "국문 저작권 문구 (null 또는 공백 아닌 1~200자)")
        String ko,

        @Schema(description = "영문 저작권 문구 (null 또는 공백 아닌 1~200자)")
        String en
    ) {
        StoreFooterContent.Text toDomain() {
            return new StoreFooterContent.Text(ko, en);
        }
    }

    public record Labels(
        @Schema(description = "국문 표시명 (1~80자)")
        @NotBlank(message = StoreFooterValidationMessage.LABELS)
        @Size(max = 80, message = StoreFooterValidationMessage.LABELS)
        @Pattern(regexp = "[^\\p{Cc}]*", message = StoreFooterValidationMessage.LABELS)
        String ko,

        @Schema(description = "영문 표시명 (1~80자)")
        @NotBlank(message = StoreFooterValidationMessage.LABELS)
        @Size(max = 80, message = StoreFooterValidationMessage.LABELS)
        @Pattern(regexp = "[^\\p{Cc}]*", message = StoreFooterValidationMessage.LABELS)
        String en
    ) {
        StoreFooterContent.Text toDomain() {
            return new StoreFooterContent.Text(ko, en);
        }
    }

    public record Link(
        @Schema(description = "링크 유형 제어 코드", allowableValues = {"INSTAGRAM", "KAKAO_CHANNEL", "CUSTOM"})
        @NotNull(message = StoreFooterValidationMessage.TYPE)
        @Pattern(regexp = "INSTAGRAM|KAKAO_CHANNEL|CUSTOM", message = StoreFooterValidationMessage.TYPE)
        String type,

        @Schema(description = "국문·영문 표시명")
        @NotNull(message = StoreFooterValidationMessage.LABELS)
        @Valid
        Labels labels,

        @Schema(description = "허용 호스트의 HTTPS URL (최대 2048자, query·fragment 금지)")
        @NotBlank(message = StoreFooterValidationMessage.URL)
        @Size(max = 2048, message = StoreFooterValidationMessage.URL)
        String url,

        @Schema(description = "표시 순서 (0~999, 숨김 포함 중복 금지)")
        @NotNull(message = StoreFooterValidationMessage.ORDER)
        @Min(value = 0, message = StoreFooterValidationMessage.ORDER)
        @Max(value = 999, message = StoreFooterValidationMessage.ORDER)
        Integer displayOrder,

        @Schema(description = "공개 노출 여부")
        @NotNull(message = StoreFooterValidationMessage.VISIBLE)
        Boolean visible
    ) {
        StoreFooterContent.Link toDomain() {
            return new StoreFooterContent.Link(type, labels == null ? null : labels.toDomain(), url, displayOrder, visible);
        }
    }

    public record Draft(
        @Schema(description = "최초 등록은 null, 기존 설정은 조회한 버전")
        @PositiveOrZero(message = StoreFooterValidationMessage.VERSION)
        Long expectedVersion,

        @Schema(description = "국문·영문 저작권 문구 (각 언어는 null 허용)")
        @NotNull(message = StoreFooterValidationMessage.COPYRIGHT)
        @Valid
        CopyrightText copyrightText,

        @Schema(description = "전체 교체할 링크 목록 (최대 20개, 빈 목록 허용)")
        @NotNull(message = StoreFooterValidationMessage.LINKS)
        @Size(max = 20, message = StoreFooterValidationMessage.LINKS)
        @Valid
        List<@NotNull(message = StoreFooterValidationMessage.LINKS) Link> links
    ) {
        public StoreFooterCommands.SaveDraft toCommand() {
            return new StoreFooterCommands.SaveDraft(expectedVersion, new StoreFooterContent(
                copyrightText == null ? null : copyrightText.toDomain(),
                links == null ? null : links.stream().map(link -> link == null ? null : link.toDomain()).toList()));
        }
    }

    public record Publish(
        @Schema(description = "조회한 버전 (현재 게시본 반복 요청은 오래된 버전 허용)")
        @NotNull(message = StoreFooterValidationMessage.VERSION)
        @PositiveOrZero(message = StoreFooterValidationMessage.VERSION)
        Long expectedVersion,

        @Schema(description = "게시할 최신 리비전 번호")
        @NotNull(message = StoreFooterValidationMessage.REVISION)
        @Positive(message = StoreFooterValidationMessage.REVISION)
        Integer revisionNumber
    ) {
        public StoreFooterCommands.Publish toCommand() {
            return new StoreFooterCommands.Publish(expectedVersion, revisionNumber);
        }
    }
}
