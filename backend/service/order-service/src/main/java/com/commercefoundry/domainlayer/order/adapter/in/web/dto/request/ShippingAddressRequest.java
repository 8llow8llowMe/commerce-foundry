package com.commercefoundry.domainlayer.order.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.validation.ValidShippingDestination;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.domain.model.ShippingAddress;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 주문 시점 스냅숏. 프론트가 주소록에서 고른 값을 보낸다. 문자열 앞뒤 공백과 빈 선택 칸을 정규화한다.
 * HTML이 아닌 텍스트다. 해외 배송지는 도시가 필수다.
 */
@ValidShippingDestination
@com.commercefoundry.domainlayer.order.adapter.in.web.dto.validation.ValidOrderShippingAddress
@Schema(description = "배송지")
public record ShippingAddressRequest(

    @Schema(description = "받는 사람 (50자 이하)", example = "홍길동")
    @NotBlank(message = OrderValidationMessage.RECIPIENT_NAME_REQUIRED)
    @Size(max = 50, message = OrderValidationMessage.RECIPIENT_NAME_LENGTH_MAX)
    String recipientName,

    @Schema(description = "국가별 연락처 — 공백·하이픈 제거 후 국내 0으로 시작하는 9~11자리, 해외 선택적 +와 숫자 7~15자리", example = "010-1234-5678")
    @NotBlank(message = OrderValidationMessage.RECIPIENT_PHONE_REQUIRED)
    String recipientPhone,

    @Schema(description = "배송 국가 (ISO 3166-1 alpha-2 대문자). KR 외는 해외 배송이다", example = "KR")
    @NotBlank(message = OrderValidationMessage.COUNTRY_CODE_REQUIRED)
    @Pattern(regexp = "^[A-Z]{2}$", message = OrderValidationMessage.COUNTRY_CODE_FORMAT)
    String countryCode,

    @Schema(description = "우편번호 — 국내(KR)는 필수 · 숫자 5자리, 해외는 선택(영문 · 숫자 · 공백 · 하이픈 20자 이하)", example = "63100", nullable = true)
    String postalCode,

    @Schema(description = "기본 주소 (200자 이하)", example = "제주특별자치도 제주시 첨단로 242")
    @NotBlank(message = OrderValidationMessage.ADDRESS_LINE1_REQUIRED)
    @Size(max = 200, message = OrderValidationMessage.ADDRESS_LINE1_LENGTH_MAX)
    String addressLine1,

    @Schema(description = "상세 주소 (200자 이하). 없으면 null", example = "101동 1001호", nullable = true)
    @Size(max = 200, message = OrderValidationMessage.ADDRESS_LINE2_LENGTH_MAX)
    String addressLine2,

    @Schema(description = "배송 요청 사항 (200자 이하). 없으면 null", example = "문 앞에 놓아 주세요", nullable = true)
    @Size(max = 200, message = OrderValidationMessage.DELIVERY_MEMO_LENGTH_MAX)
    String deliveryMemo,

    @Schema(description = "도시 — 해외 배송 필수", nullable = true)
    @Size(max = 100, message = OrderValidationMessage.CITY_LENGTH_MAX)
    String city,

    @Schema(description = "주 · 도", nullable = true)
    @Size(max = 100, message = OrderValidationMessage.STATE_PROVINCE_LENGTH_MAX)
    String stateProvince
) implements ValidShippingDestination.Fields {

    public ShippingAddressRequest {
        recipientName = blankToNull(recipientName);
        recipientPhone = blankToNull(recipientPhone);
        countryCode = blankToNull(countryCode);
        postalCode = blankToNull(postalCode);
        addressLine1 = blankToNull(addressLine1);
        addressLine2 = blankToNull(addressLine2);
        city = blankToNull(city);
        stateProvince = blankToNull(stateProvince);
        deliveryMemo = blankToNull(deliveryMemo);
    }

    public ShippingAddress toShippingAddress() {
        return new ShippingAddress(recipientName.strip(), recipientPhone.strip(), countryCode, normalizedPostalCode(),
            addressLine1.strip(), blankToNull(addressLine2), blankToNull(city), blankToNull(stateProvince), blankToNull(deliveryMemo));
    }

    @Override
    public String toString() {
        // 개인정보 — 요청 DTO 도 로그로 새지 않게 국가만 보인다
        return "ShippingAddressRequest[countryCode=" + countryCode + "]";
    }

    static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
