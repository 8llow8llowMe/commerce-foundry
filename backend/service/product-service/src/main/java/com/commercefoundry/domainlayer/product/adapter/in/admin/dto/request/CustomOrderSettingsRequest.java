package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.application.command.ProductCustomOrderCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductEditValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 상품 등록 본문의 맞춤 제작 설정 (#9 PR-B). 생략하면 맞춤 제작 꺼짐 · 항목 없음이다. */
@Schema(description = "맞춤 제작 설정")
public record CustomOrderSettingsRequest(
    @Schema(description = "맞춤 제작 상품인가", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ProductEditValidationMessage.CUSTOM_ORDER_ENABLED_REQUIRED)
    Boolean enabled,

    @Schema(description = "고객 안내 문구 (500자 이하, 줄바꿈 가능)", nullable = true)
    @Size(max = 500, message = ProductEditValidationMessage.CUSTOM_ORDER_NOTICE_INVALID)
    @Pattern(regexp = CustomOrderSettingsRequest.NOTICE_TEXT, message = ProductEditValidationMessage.CUSTOM_ORDER_NOTICE_INVALID)
    String notice,

    @Schema(description = "제작 소요일 (1~365)", example = "14", nullable = true)
    @Min(value = 1, message = ProductEditValidationMessage.CUSTOM_ORDER_LEAD_TIME_RANGE)
    @Max(value = 365, message = ProductEditValidationMessage.CUSTOM_ORDER_LEAD_TIME_RANGE)
    Integer leadTimeDays,

    @Schema(description = "입력 항목 (0~20개). 배열 순서가 노출 순서", nullable = true)
    @Size(max = 20, message = ProductEditValidationMessage.CUSTOM_ORDER_FIELDS_SIZE)
    List<@NotNull(message = ProductEditValidationMessage.CUSTOM_ORDER_FIELDS_SIZE) @Valid CustomOrderFieldRequest> fields
) {

    /**
     * 안내 문구 — {@code ProductValidationMessage.PLAIN_TEXT} 에 줄바꿈 문자(CR · LF)만 더 허용한다.
     * 문자 클래스 하나의 반복이라 재귀하지 않는다 — {@code (?:A|B)*} 꼴은 글자마다 재귀해 수천 자에서 StackOverflowError(500)가 난다.
     * {@code @Size} 를 넘어도 {@code @Pattern} 은 돌기 때문에 길이 상한만으로는 막히지 않는다.
     */
    static final String NOTICE_TEXT = "[[^\\p{Cc}\\p{Cf}\\p{Cs}\\p{Zl}\\p{Zp}]\\r\\n]*";

    public ProductCustomOrderCommand toCommand() {
        return new ProductCustomOrderCommand(enabled, CustomOrderFieldRequest.blankToNull(notice), leadTimeDays,
            CustomOrderFieldRequest.toCommands(fields));
    }
}
