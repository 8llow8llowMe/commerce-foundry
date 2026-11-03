package com.commercefoundry.domainlayer.payment.adapter.in.webhook.dto.request;

import com.commercefoundry.domainlayer.payment.application.command.PaymentWebhookCommand;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentWebhookValidationMessage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "토스 결제 상태 알림. 상태·금액 등 나머지 원문은 사용하지 않는다")
public record TossWebhookRequest(
    @Schema(description = "지원 이벤트")
    @NotNull(message = PaymentWebhookValidationMessage.EVENT)
    @Pattern(regexp = "PAYMENT_STATUS_CHANGED", message = PaymentWebhookValidationMessage.EVENT) String eventType,

    @Schema(description = "공급자 이벤트 생성 시각")
    @NotBlank(message = PaymentWebhookValidationMessage.CREATED)
    @Size(max = 40, message = PaymentWebhookValidationMessage.CREATED) String createdAt,

    @Schema(description = "결제 식별 정보")
    @NotNull(message = PaymentWebhookValidationMessage.DATA) @Valid Data data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
        @Schema(description = "PG 주문번호")
        @NotNull(message = PaymentWebhookValidationMessage.ORDER)
        @Pattern(regexp = "[a-zA-Z0-9_-]{6,64}", message = PaymentWebhookValidationMessage.ORDER) String orderId,

        @Schema(description = "기존 결제 시도와 대조할 키")
        @NotBlank(message = PaymentWebhookValidationMessage.KEY)
        @Size(max = 200, message = PaymentWebhookValidationMessage.KEY) String paymentKey
    ) {
        @Override public String toString() { return "Data[]"; }
    }

    @AssertTrue(message = PaymentWebhookValidationMessage.CREATED)
    public boolean isCreatedAtValid() {
        if (createdAt == null || createdAt.length() > 40) { return false; }
        try { return year(OffsetDateTime.parse(createdAt).getYear()); }
        catch (DateTimeParseException noZone) {
            try { return year(LocalDateTime.parse(createdAt).getYear()); }
            catch (DateTimeParseException invalid) { return false; }
        }
    }

    private static boolean year(int year) { return year >= 1970 && year <= 9999; }

    public PaymentWebhookCommand toCommand() {
        String normalized;
        try { normalized = OffsetDateTime.parse(createdAt).toInstant().toString(); }
        catch (DateTimeParseException noZone) { normalized = LocalDateTime.parse(createdAt).toString(); }
        return new PaymentWebhookCommand(eventType, normalized, data.orderId(), data.paymentKey());
    }

    @Override public String toString() { return "TossWebhookRequest[]"; }
}
