package com.commercefoundry.domainlayer.storenavigation.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.storenavigation.application.command.StoreNavigationCommands;
import com.commercefoundry.domainlayer.storenavigation.application.exception.StoreNavigationValidationMessage;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent;
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

/** 형식만 여기서 거른다. 상위 존재·순환·깊이·형제 순서·유형별 URL/카테고리 규칙은 도메인 검증이 같은 코드로 낸다. */
public final class StoreNavigationAdminRequests {
    private static final String KEY = "[a-z0-9][a-z0-9-]{0,39}";

    private StoreNavigationAdminRequests() {
    }

    public record Labels(
        @Schema(description = "국문 표시명 (1~40자)")
        @NotBlank(message = StoreNavigationValidationMessage.LABELS)
        @Size(max = 40, message = StoreNavigationValidationMessage.LABELS)
        @Pattern(regexp = "[^\\p{Cc}]*", message = StoreNavigationValidationMessage.LABELS)
        String ko,

        @Schema(description = "영문 표시명 (1~40자)")
        @NotBlank(message = StoreNavigationValidationMessage.LABELS)
        @Size(max = 40, message = StoreNavigationValidationMessage.LABELS)
        @Pattern(regexp = "[^\\p{Cc}]*", message = StoreNavigationValidationMessage.LABELS)
        String en
    ) {
        StoreNavigationContent.Text toDomain() {
            return new StoreNavigationContent.Text(ko, en);
        }
    }

    public record Item(
        @Schema(description = "리비전 안 편집용 항목 key (영문 소문자·숫자로 시작, 소문자·숫자·하이픈 1~40자, 목록 안 유일)", example = "outer")
        @NotNull(message = StoreNavigationValidationMessage.KEY)
        @Pattern(regexp = KEY, message = StoreNavigationValidationMessage.KEY)
        String key,

        @Schema(description = "상위 항목 key (최상위 null, 최대 3단계)", example = "women", nullable = true)
        @Pattern(regexp = KEY, message = StoreNavigationValidationMessage.PARENT)
        String parentKey,

        @Schema(description = "메뉴 유형 제어 코드", allowableValues = {"INTERNAL", "EXTERNAL", "CATEGORY", "NONE"})
        @NotNull(message = StoreNavigationValidationMessage.TYPE)
        @Pattern(regexp = "INTERNAL|EXTERNAL|CATEGORY|NONE", message = StoreNavigationValidationMessage.TYPE)
        String type,

        @Schema(description = "국문·영문 표시명")
        @NotNull(message = StoreNavigationValidationMessage.LABELS)
        @Valid
        Labels labels,

        @Schema(description = "INTERNAL 은 / 로 시작하는 내부 경로(최대 512자), EXTERNAL 은 허용 호스트의 HTTPS URL. CATEGORY·NONE 은 null",
            example = "/collections/new", nullable = true)
        @Size(max = 2048, message = StoreNavigationValidationMessage.URL)
        String url,

        @Schema(description = "카테고리 아이디 (CATEGORY 만, 양수. JSON 문자열도 받음)", example = "42", nullable = true)
        @Positive(message = StoreNavigationValidationMessage.CATEGORY)
        Long categoryId,

        @Schema(description = "같은 상위 항목 안 표시 순서 (0~999, 숨김 포함 중복 금지)")
        @NotNull(message = StoreNavigationValidationMessage.ORDER)
        @Min(value = 0, message = StoreNavigationValidationMessage.ORDER)
        @Max(value = 999, message = StoreNavigationValidationMessage.ORDER)
        Integer displayOrder,

        @Schema(description = "공개 노출 여부 (숨김 항목의 하위도 공개되지 않음)")
        @NotNull(message = StoreNavigationValidationMessage.VISIBLE)
        Boolean visible
    ) {
        StoreNavigationContent.Item toDomain() {
            return new StoreNavigationContent.Item(key, parentKey, type, labels == null ? null : labels.toDomain(), url, categoryId,
                displayOrder, visible);
        }
    }

    public record Draft(
        @Schema(description = "최초 등록은 null, 기존 설정은 조회한 버전")
        @PositiveOrZero(message = StoreNavigationValidationMessage.VERSION)
        Long expectedVersion,

        @Schema(description = "전체 교체할 평평한 항목 목록 (parentKey 로 트리 구성, 전체 최대 60개, 빈 목록 허용)")
        @NotNull(message = StoreNavigationValidationMessage.ITEMS)
        @Size(max = 60, message = StoreNavigationValidationMessage.ITEMS)
        @Valid
        List<@NotNull(message = StoreNavigationValidationMessage.ITEMS) Item> items
    ) {
        public StoreNavigationCommands.SaveDraft toCommand() {
            return new StoreNavigationCommands.SaveDraft(expectedVersion, new StoreNavigationContent(
                items == null ? null : items.stream().map(item -> item == null ? null : item.toDomain()).toList()));
        }
    }
}
