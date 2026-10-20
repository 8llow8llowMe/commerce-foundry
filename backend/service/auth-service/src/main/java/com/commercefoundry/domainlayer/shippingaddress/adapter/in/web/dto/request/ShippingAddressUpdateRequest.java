package com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.validation.ShippingAddressFields;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.validation.ValidShippingAddressRegion;
import com.commercefoundry.domainlayer.shippingaddress.application.command.ShippingAddressCommand;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressValidationMessage;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddressContent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 배송지 전체 교체. 칸 규칙은 추가 요청과 같다. 보내지 않은 선택 칸은 지워진다(부분 수정이 아니다).
 * 기본 배송지 여부는 이 요청으로 바꾸지 않는다 — {@code isDefault} 칸이 없고, 보내도 무시된다. 기본 지정은 {@code PUT .../{addressId}/default} 다.
 */
@ValidShippingAddressRegion
@Schema(description = "배송지 전체 교체 요청. 보내지 않은 선택 칸은 지운다. 기본 배송지 여부는 바꾸지 않는다")
public record ShippingAddressUpdateRequest(

    @Schema(description = "별칭 (선택, 30자 이하) — 집 · 회사 등. 비거나 공백뿐이면 없음", example = "회사", nullable = true)
    @Size(max = ShippingAddressContent.LABEL_MAX_LENGTH, message = ShippingAddressValidationMessage.LABEL_LENGTH_INVALID)
    String label,

    @Schema(description = "받는 사람 이름 — 1~50자. 앞뒤 공백을 지우고 저장한다", example = "홍길동")
    @NotBlank(message = ShippingAddressValidationMessage.RECIPIENT_NAME_INVALID)
    @Size(max = ShippingAddressContent.RECIPIENT_NAME_MAX_LENGTH, message = ShippingAddressValidationMessage.RECIPIENT_NAME_INVALID)
    String recipientName,

    @Schema(
        description = "받는 사람 연락처 — 하이픈 · 공백을 빼고 저장한다. KR 이면 0으로 시작하는 9~11자리(휴대폰 · 지역번호 유선), "
            + "그 밖은 + 로 시작할 수 있는 7~15자리 숫자(E.164)",
        example = "02-123-4567"
    )
    @NotBlank(message = ShippingAddressValidationMessage.RECIPIENT_PHONE_REQUIRED)
    String recipientPhone,

    @Schema(description = "국가 코드 (ISO 3166-1 alpha-2 대문자). 형식만 본다", example = "KR")
    @NotBlank(message = ShippingAddressValidationMessage.COUNTRY_CODE_INVALID)
    @Pattern(regexp = ShippingAddressContent.COUNTRY_CODE_REGEX, message = ShippingAddressValidationMessage.COUNTRY_CODE_INVALID)
    String countryCode,

    @Schema(description = "우편번호. KR 이면 필수(숫자 5자리), 그 밖은 선택(영문 · 숫자 · 공백 · 하이픈 20자 이하)", example = "06236", nullable = true)
    String postalCode,

    @Schema(description = "주소 — 1~200자. 국내는 도로명 · 지번 주소, 해외는 street address", example = "서울특별시 강남구 테헤란로 123")
    @NotBlank(message = ShippingAddressValidationMessage.ADDRESS_LINE1_REQUIRED)
    @Size(max = ShippingAddressContent.ADDRESS_LINE_MAX_LENGTH, message = ShippingAddressValidationMessage.ADDRESS_LENGTH_INVALID)
    String addressLine1,

    @Schema(description = "상세 주소 (선택, 200자 이하)", example = "4층 401호", nullable = true)
    @Size(max = ShippingAddressContent.ADDRESS_LINE_MAX_LENGTH, message = ShippingAddressValidationMessage.ADDRESS_LENGTH_INVALID)
    String addressLine2,

    @Schema(description = "도시 (100자 이하). KR 이 아니면 필수, KR 이면 선택", example = "Seoul", nullable = true)
    @Size(max = ShippingAddressContent.CITY_MAX_LENGTH, message = ShippingAddressValidationMessage.ADDRESS_LENGTH_INVALID)
    String city,

    @Schema(description = "주 · 도 (선택, 100자 이하)", example = "California", nullable = true)
    @Size(max = ShippingAddressContent.STATE_PROVINCE_MAX_LENGTH, message = ShippingAddressValidationMessage.ADDRESS_LENGTH_INVALID)
    String stateProvince
) implements ShippingAddressFields {

    public ShippingAddressCommand toCommand() {
        return new ShippingAddressCommand(label, recipientName, recipientPhone, countryCode, postalCode, addressLine1, addressLine2, city, stateProvince);
    }

    /** 개인정보(받는 사람 · 연락처 · 주소)를 싣지 않는다. */
    @Override
    public String toString() {
        return "ShippingAddressUpdateRequest[countryCode=" + countryCode + "]";
    }
}
