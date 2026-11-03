package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.application.command.ProductCustomOrderCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductEditValidationMessage;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 맞춤 제작 입력 항목 하나 (#9 PR-B) — 등록의 {@code customOrder.fields[]} 와 {@code PUT /custom-order} 의 {@code fields[]}. */
@Schema(description = "맞춤 제작 입력 항목 (치수 · 요청 사항 등)")
public record CustomOrderFieldRequest(
    @Schema(description = "입력 항목 키. 고객 입력 · 주문이 이 키로 값을 가리켜 저장 뒤에는 바꾸지 않는다 — 같은 키는 같은 항목, 빠진 키는 삭제",
        example = "chest", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ProductEditValidationMessage.CUSTOM_ORDER_FIELD_KEY_INVALID)
    @Pattern(regexp = "[a-z][a-z0-9_]{0,29}", message = ProductEditValidationMessage.CUSTOM_ORDER_FIELD_KEY_INVALID)
    String fieldKey,

    @Schema(description = "고객에게 보이는 이름 (1~50자)", example = "가슴둘레", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = ProductEditValidationMessage.CUSTOM_ORDER_FIELD_LABEL_INVALID)
    @Size(max = 50, message = ProductEditValidationMessage.CUSTOM_ORDER_FIELD_LABEL_INVALID)
    @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductEditValidationMessage.CUSTOM_ORDER_FIELD_LABEL_INVALID)
    String label,

    @Schema(description = "NUMBER(숫자) · TEXT(글자)", example = "NUMBER", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ProductEditValidationMessage.CUSTOM_ORDER_FIELD_INPUT_TYPE_REQUIRED)
    CustomOrderInputType inputType,

    @Schema(description = "단위 (10자 이하)", example = "cm", nullable = true)
    @Size(max = 10, message = ProductEditValidationMessage.CUSTOM_ORDER_FIELD_TEXT_INVALID)
    @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductEditValidationMessage.CUSTOM_ORDER_FIELD_TEXT_INVALID)
    String unit,

    @Schema(description = "필수 입력 여부", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ProductEditValidationMessage.CUSTOM_ORDER_FIELD_REQUIRED_REQUIRED)
    Boolean required,

    @Schema(description = "NUMBER 최소값 (소수 둘째 자리까지). TEXT 는 비운다", example = "60", nullable = true)
    BigDecimal minValue,

    @Schema(description = "NUMBER 최대값 · TEXT 최대 글자 수(1~500 정수)", example = "150", nullable = true)
    BigDecimal maxValue,

    @Schema(description = "NUMBER 증분 (0보다 큼). TEXT 는 비운다", example = "0.5", nullable = true)
    BigDecimal stepValue,

    @Schema(description = "도움말 (200자 이하)", example = "겨드랑이 아래 가장 넓은 곳을 재 주세요", nullable = true)
    @Size(max = 200, message = ProductEditValidationMessage.CUSTOM_ORDER_FIELD_TEXT_INVALID)
    @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductEditValidationMessage.CUSTOM_ORDER_FIELD_TEXT_INVALID)
    String helpText
) {

    ProductCustomOrderCommand.Field toCommand() {
        return new ProductCustomOrderCommand.Field(fieldKey, label.strip(), inputType, blankToNull(unit), required,
            minValue, maxValue, stepValue, blankToNull(helpText));
    }

    static List<ProductCustomOrderCommand.Field> toCommands(List<CustomOrderFieldRequest> fields) {
        return fields == null ? List.of() : fields.stream().map(CustomOrderFieldRequest::toCommand).toList();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
