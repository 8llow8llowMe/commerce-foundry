package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.request.ShippingPolicyReplaceRequest.MoneyRequest;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.request.ShippingPolicyReplaceRequest.PositiveMoneyRequest;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand.RateCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand.ZoneCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 해외 배송비 정책 전체 교체. 금액 모양(필수 · 0 이상 · 상한)은 국내 요청과 같은 {@link MoneyRequest} 를 쓴다.
 * 통화 일치 · 국내(KR) 포함 · 그룹 이름 중복 · 국가 중복 · 구간 상한 중복은 Processor 가 도메인 오류 코드(003 · 009 · 010 · 007 · 008)로 본다.
 */
@Schema(description = "해외 배송비 정책 전체 교체 요청")
public record InternationalShippingPolicyReplaceRequest(

    @Schema(description = "기본료 — 모든 해외 배송에 무게 요금과 함께 붙는다(취급 수수료). 0 이상", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = DeliveryValidationMessage.BASE_FEE_REQUIRED)
    @Valid
    MoneyRequest baseFee,

    @Schema(
        description = "무료배송 기준 주문 금액(할인 후 상품 합계) — 이 금액 이상이면 기본료 · 무게 요금이 모두 없다. null 이면 무료배송 없음. 0 은 받지 않는다",
        nullable = true
    )
    @Valid
    PositiveMoneyRequest freeShippingThreshold,

    @Schema(description = "관세 · 부가세 고객 부담 안내 문구 (1000자 이하). 비우거나 공백뿐이면 null 로 저장한다", nullable = true)
    @Size(max = 1000, message = DeliveryValidationMessage.CUSTOMS_NOTICE_LENGTH)
    String customsNotice,

    @Schema(description = "국가 그룹 (1~50개). 이 순서가 표시 순서다. 한 국가는 한 그룹에만, KR 은 넣을 수 없다", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = DeliveryValidationMessage.ZONES_REQUIRED)
    @Size(max = 50, message = DeliveryValidationMessage.ZONES_SIZE_MAX)
    List<@NotNull(message = DeliveryValidationMessage.ZONE_REQUIRED) @Valid ZoneRequest> zones,

    /** 해외 정책에는 도서산간 구간이 없다. 국내 폼을 그대로 보내 조용히 버려지지 않게 받아서 거절한다 — 문서에는 싣지 않는다. */
    @Schema(hidden = true)
    @Null(message = DeliveryValidationMessage.REMOTE_AREAS_NOT_ALLOWED)
    Object remoteAreas,

    @Schema(description = "조회한 정책의 version. 정책을 처음 만들 때만 null — 다르면 409(DELIVERY_006)", example = "3", nullable = true)
    Long version
) {

    public InternationalShippingPolicyReplaceCommand toCommand() {
        return new InternationalShippingPolicyReplaceCommand(
            baseFee.toMoney(),
            freeShippingThreshold == null ? null : freeShippingThreshold.toMoney(),
            customsNotice == null || customsNotice.isBlank() ? null : customsNotice.strip(),
            zones.stream().map(ZoneRequest::toCommand).toList(),
            version
        );
    }

    @Schema(description = "해외 배송 국가 그룹 — 같은 무게 요금표를 쓰는 국가들")
    public record ZoneRequest(

        @Schema(description = "국가 그룹 이름 (50자 이하, 정책 안에서 유일)", example = "아시아", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = DeliveryValidationMessage.ZONE_NAME_REQUIRED)
        @Size(max = 50, message = DeliveryValidationMessage.ZONE_NAME_LENGTH)
        String name,

        @Schema(description = "국가 코드 (ISO 3166-1 alpha-2 대문자, 1~250개). KR 제외", example = "[\"JP\", \"SG\"]", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = DeliveryValidationMessage.ZONE_COUNTRY_CODES_REQUIRED)
        @Size(max = 250, message = DeliveryValidationMessage.ZONE_COUNTRY_CODES_SIZE_MAX)
        List<
            @NotNull(message = DeliveryValidationMessage.COUNTRY_CODE_REQUIRED)
            @Pattern(regexp = "^[A-Z]{2}$", message = DeliveryValidationMessage.COUNTRY_CODE_FORMAT) String
        > countryCodes,

        @Schema(description = "무게 구간 (1~100개). 상한(포함)이 서로 달라야 한다 — 순서는 상관없고 상한 오름차순으로 저장한다", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = DeliveryValidationMessage.ZONE_RATES_REQUIRED)
        @Size(max = 100, message = DeliveryValidationMessage.ZONE_RATES_SIZE_MAX)
        List<@NotNull(message = DeliveryValidationMessage.ZONE_RATE_REQUIRED) @Valid RateRequest> rates
    ) {

        ZoneCommand toCommand() {
            return new ZoneCommand(name, countryCodes, rates.stream().map(RateRequest::toCommand).toList());
        }
    }

    @Schema(description = "무게 구간 — 바로 아래 구간 상한 + 1g 부터 이 상한(포함)까지의 요금")
    public record RateRequest(

        @Schema(description = "상한 무게 (그램, 포함). 1 이상 1,000,000 이하", example = "500", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = DeliveryValidationMessage.MAX_WEIGHT_GRAMS_REQUIRED)
        @Positive(message = DeliveryValidationMessage.MAX_WEIGHT_GRAMS_POSITIVE)
        @Max(value = MAX_WEIGHT_GRAMS, message = DeliveryValidationMessage.MAX_WEIGHT_GRAMS_MAX)
        Integer maxWeightGrams,

        @Schema(description = "무게 요금 (0 이상) — 정책의 모든 금액과 통화가 같아야 한다", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = DeliveryValidationMessage.RATE_FEE_REQUIRED)
        @Valid
        MoneyRequest fee
    ) {

        /** 무게 상한의 상한(그램) — 1톤. 국제 특송 한 건으로 넉넉하고, 오타(단위 착각)를 막는다. */
        static final int MAX_WEIGHT_GRAMS = 1_000_000;

        RateCommand toCommand() {
            return new RateCommand(maxWeightGrams, fee.toMoney());
        }
    }
}
