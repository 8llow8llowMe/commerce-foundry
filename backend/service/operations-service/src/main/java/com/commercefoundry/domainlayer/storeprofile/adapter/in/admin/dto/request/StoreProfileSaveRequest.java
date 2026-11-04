package com.commercefoundry.domainlayer.storeprofile.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.storeprofile.application.command.StoreProfileSaveCommand;
import com.commercefoundry.domainlayer.storeprofile.application.exception.StoreProfileValidationMessage;
import com.commercefoundry.domainlayer.storeprofile.domain.model.StoreProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record StoreProfileSaveRequest(
    @Schema(description = "최초 생성은 null, 수정은 직전 조회 version")
    @PositiveOrZero(message = StoreProfileValidationMessage.VERSION)
    Long expectedVersion,

    @Schema(description = "쇼핑몰 이름 (최대 100자)")
    @NotBlank(message = StoreProfileValidationMessage.STORE_NAME)
    @Size(max = 100, message = StoreProfileValidationMessage.STORE_NAME)
    @Pattern(regexp = "[^\\p{Cc}]*", message = StoreProfileValidationMessage.STORE_NAME)
    String storeName,

    @Schema(description = "상호 (최대 200자)")
    @NotBlank(message = StoreProfileValidationMessage.COMPANY_NAME)
    @Size(max = 200, message = StoreProfileValidationMessage.COMPANY_NAME)
    @Pattern(regexp = "[^\\p{Cc}]*", message = StoreProfileValidationMessage.COMPANY_NAME)
    String companyName,

    @Schema(description = "대표자 (최대 100자)")
    @NotBlank(message = StoreProfileValidationMessage.REPRESENTATIVE_NAME)
    @Size(max = 100, message = StoreProfileValidationMessage.REPRESENTATIVE_NAME)
    @Pattern(regexp = "[^\\p{Cc}]*", message = StoreProfileValidationMessage.REPRESENTATIVE_NAME)
    String representativeName,

    @Schema(description = "ISO 3166-1 alpha-2 국가 코드 (최대 2자)")
    @NotBlank(message = StoreProfileValidationMessage.COUNTRY_CODE)
    @Size(max = 2, message = StoreProfileValidationMessage.COUNTRY_CODE)
    @Pattern(regexp = "[^\\p{Cc}]*", message = StoreProfileValidationMessage.COUNTRY_CODE)
    @Pattern(regexp = "[A-Z]{2}", message = StoreProfileValidationMessage.COUNTRY_CODE)
    String countryCode,

    @Schema(description = "사업자 식별 번호 (최대 100자)")
    @NotBlank(message = StoreProfileValidationMessage.BUSINESS_REGISTRATION_NUMBER)
    @Size(max = 100, message = StoreProfileValidationMessage.BUSINESS_REGISTRATION_NUMBER)
    @Pattern(regexp = "[^\\p{Cc}]*", message = StoreProfileValidationMessage.BUSINESS_REGISTRATION_NUMBER)
    String businessRegistrationNumber,

    @Schema(description = "통신판매 신고 번호 (최대 100자)")
    @Size(max = 100, message = StoreProfileValidationMessage.MAIL_ORDER_REGISTRATION_NUMBER)
    @Pattern(regexp = "[^\\p{Cc}]*", message = StoreProfileValidationMessage.MAIL_ORDER_REGISTRATION_NUMBER)
    String mailOrderRegistrationNumber,

    @Schema(description = "공개 사업장 주소 (최대 500자)")
    @NotBlank(message = StoreProfileValidationMessage.ADDRESS)
    @Size(max = 500, message = StoreProfileValidationMessage.ADDRESS)
    @Pattern(regexp = "[^\\p{Cc}]*", message = StoreProfileValidationMessage.ADDRESS)
    String address,

    @Schema(description = "고객센터 이메일 (최대 254자)")
    @NotBlank(message = StoreProfileValidationMessage.CUSTOMER_SERVICE_EMAIL)
    @Size(max = 254, message = StoreProfileValidationMessage.CUSTOMER_SERVICE_EMAIL)
    @Pattern(regexp = "[^\\p{Cc}]*", message = StoreProfileValidationMessage.CUSTOMER_SERVICE_EMAIL)
    @Email(message = StoreProfileValidationMessage.CUSTOMER_SERVICE_EMAIL)
    String customerServiceEmail,

    @Schema(description = "고객센터 전화 (최대 50자)")
    @Size(max = 50, message = StoreProfileValidationMessage.CUSTOMER_SERVICE_PHONE)
    @Pattern(regexp = "[^\\p{Cc}]*", message = StoreProfileValidationMessage.CUSTOMER_SERVICE_PHONE)
    String customerServicePhone,

    @Schema(description = "고객센터 운영 시간 (최대 500자)")
    @Size(max = 500, message = StoreProfileValidationMessage.CUSTOMER_SERVICE_HOURS)
    @Pattern(regexp = "[^\\p{Cc}]*", message = StoreProfileValidationMessage.CUSTOMER_SERVICE_HOURS)
    String customerServiceHours
) {

    public StoreProfileSaveCommand toCommand() {
        return new StoreProfileSaveCommand(expectedVersion, new StoreProfile(
            storeName, companyName, representativeName, countryCode, businessRegistrationNumber, mailOrderRegistrationNumber,
            address, customerServiceEmail, customerServicePhone, customerServiceHours
        ));
    }
}
