package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand.RemoteAreaCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryValidationMessage;
import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 국내 배송비 정책 전체 교체. 통화 일치 · 구간 순서 · 구간 겹침은 Processor 가 도메인 오류 코드(003 · 004 · 005)로 본다.
 */
@Schema(description = "국내 배송비 정책 전체 교체 요청")
public record ShippingPolicyReplaceRequest(

    @Schema(description = "기본 배송비", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = DeliveryValidationMessage.BASE_FEE_REQUIRED)
    @Valid
    MoneyRequest baseFee,

    @Schema(description = "무료배송 기준 주문 금액(할인 후 상품 합계) — 이 금액 이상이면 기본 배송비가 없다. null 이면 무료배송 없음. 0 은 받지 않는다", nullable = true)
    @Valid
    PositiveMoneyRequest freeShippingThreshold,

    @Schema(description = "도서산간 구간 (최대 200개). 서로 겹칠 수 없다. 없으면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = DeliveryValidationMessage.REMOTE_AREAS_REQUIRED)
    @Size(max = 200, message = DeliveryValidationMessage.REMOTE_AREAS_SIZE_MAX)
    List<@NotNull(message = DeliveryValidationMessage.REMOTE_AREA_REQUIRED) @Valid RemoteAreaRequest> remoteAreas,

    @Schema(description = "조회한 정책의 version. 정책을 처음 만들 때만 null — 다르면 409(DELIVERY_006)", example = "3", nullable = true)
    Long version
) {

    /**
     * 금액 상한(최소 화폐 단위). 배송비 정책 금액으로 넉넉하고, 기본 배송비 + 추가 배송비 합이 {@code long} 을 넘어
     * {@code Math.addExact} 가 500 을 내는 일이 없게 막는다.
     */
    static final long MAX_AMOUNT = 100_000_000L;

    /** @param adminMemberId access 토큰의 memberId — 본문으로 받지 않는다 */
    public ShippingPolicyReplaceCommand toCommand(long adminMemberId) {
        return new ShippingPolicyReplaceCommand(
            baseFee.toMoney(),
            freeShippingThreshold == null ? null : freeShippingThreshold.toMoney(),
            remoteAreas.stream().map(RemoteAreaRequest::toCommand).toList(),
            version,
            adminMemberId
        );
    }

    @Schema(description = "금액 — 최소 화폐 단위 정수와 통화")
    public record MoneyRequest(

        @Schema(description = "최소 화폐 단위 금액 (KRW 는 원). 0 이상 100,000,000 이하", example = "3000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = DeliveryValidationMessage.AMOUNT_REQUIRED)
        @PositiveOrZero(message = DeliveryValidationMessage.AMOUNT_NON_NEGATIVE)
        @Max(value = MAX_AMOUNT, message = DeliveryValidationMessage.AMOUNT_MAX)
        Long amount,

        @Schema(description = "통화 코드 — 정책의 모든 금액이 같아야 한다", example = "KRW", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = DeliveryValidationMessage.CURRENCY_REQUIRED)
        CurrencyCode currency
    ) {

        Money toMoney() {
            return Money.of(amount, currency);
        }
    }

    @Schema(description = "0 보다 큰 금액 — 최소 화폐 단위 정수와 통화")
    public record PositiveMoneyRequest(

        @Schema(description = "최소 화폐 단위 금액 (KRW 는 원). 0 보다 크고 100,000,000 이하", example = "50000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = DeliveryValidationMessage.AMOUNT_REQUIRED)
        @Positive(message = DeliveryValidationMessage.FREE_SHIPPING_THRESHOLD_POSITIVE)
        @Max(value = MAX_AMOUNT, message = DeliveryValidationMessage.AMOUNT_MAX)
        Long amount,

        @Schema(description = "통화 코드 — 정책의 모든 금액이 같아야 한다", example = "KRW", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = DeliveryValidationMessage.CURRENCY_REQUIRED)
        CurrencyCode currency
    ) {

        Money toMoney() {
            return Money.of(amount, currency);
        }
    }

    @Schema(description = "도서산간 우편번호 구간 — 양 끝을 포함한다")
    public record RemoteAreaRequest(

        @Schema(description = "구간 이름 (50자 이하)", example = "제주", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = DeliveryValidationMessage.REMOTE_AREA_NAME_REQUIRED)
        @Size(max = 50, message = DeliveryValidationMessage.REMOTE_AREA_NAME_LENGTH)
        String name,

        @Schema(description = "시작 우편번호 (포함, 숫자 5자리)", example = "63000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = DeliveryValidationMessage.POSTAL_CODE_REQUIRED)
        @Pattern(regexp = "^[0-9]{5}$", message = DeliveryValidationMessage.POSTAL_CODE_FORMAT)
        String postalCodeFrom,

        @Schema(description = "끝 우편번호 (포함, 숫자 5자리). 시작보다 작을 수 없다", example = "63644", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = DeliveryValidationMessage.POSTAL_CODE_REQUIRED)
        @Pattern(regexp = "^[0-9]{5}$", message = DeliveryValidationMessage.POSTAL_CODE_FORMAT)
        String postalCodeTo,

        @Schema(description = "추가 배송비 (0 이상) — 무료배송이어도 받는다", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = DeliveryValidationMessage.SURCHARGE_REQUIRED)
        @Valid
        MoneyRequest surcharge
    ) {

        RemoteAreaCommand toCommand() {
            return new RemoteAreaCommand(name, new PostalCode(postalCodeFrom), new PostalCode(postalCodeTo), surcharge.toMoney());
        }
    }
}
