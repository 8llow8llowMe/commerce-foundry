package com.commercefoundry.domainlayer.storeprofile.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StoreProfileErrorCode {

    NOT_CONFIGURED("STORE_PROFILE_001", "쇼핑몰 정보가 아직 등록되지 않았습니다.", HttpStatus.NOT_FOUND),
    VERSION_CONFLICT("STORE_PROFILE_020", "쇼핑몰 정보가 변경되었습니다. 다시 조회해 주세요.", HttpStatus.CONFLICT),
    INVALID_REQUEST("STORE_PROFILE_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST);

    private final String code;

    private final String message;

    private final HttpStatus httpStatus;
}
