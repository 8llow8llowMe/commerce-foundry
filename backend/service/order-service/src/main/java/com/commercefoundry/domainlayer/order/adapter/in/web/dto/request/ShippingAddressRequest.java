package com.commercefoundry.domainlayer.order.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.validation.ValidShippingDestination;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.domain.model.ShippingAddress;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 주문 배송지 — 주문 시점 스냅숏으로 저장한다. 회원 주소록(#7)이 생기면 프론트가 주소록 값을 이 모양으로 채워 보낸다. 문자열은 앞뒤 공백을 자르고, 선택 칸이 비면 null 로 둔다.
 * 원문을 그대로 저장 · 응답한다(HTML 이 아니다 — 화면 · 메일 · CSV 는 텍스트로 이스케이프한다). 해외 배송지의 도시 · 주 칸은 해외 주문을 열기 전 후속 이슈다.
 */
@ValidShippingDestination
@Schema(description = "배송지")
public record ShippingAddressRequest(

    @Schema(description = "받는 사람 (50자 이하)", example = "홍길동")
    @NotBlank(message = OrderValidationMessage.RECIPIENT_NAME_REQUIRED)
    @Size(max = 50, message = OrderValidationMessage.RECIPIENT_NAME_LENGTH_MAX)
    String recipientName,

    @Schema(description = "받는 사람 연락처 — 숫자 · + · - · ( ) · 공백 7~20자", example = "010-1234-5678")
    @NotBlank(message = OrderValidationMessage.RECIPIENT_PHONE_REQUIRED)
    @Pattern(regexp = "^[0-9+\\-() ]{7,20}$", message = OrderValidationMessage.RECIPIENT_PHONE_FORMAT)
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
    String deliveryMemo
) implements ValidShippingDestination.Fields {

    public ShippingAddress toShippingAddress() {
        return new ShippingAddress(recipientName.strip(), recipientPhone.strip(), countryCode, normalizedPostalCode(),
            addressLine1.strip(), blankToNull(addressLine2), blankToNull(deliveryMemo));
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
