package com.commercefoundry.domainlayer.storebrand.application.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class BrandAssetException extends RuntimeException {
    private final Code code;

    public BrandAssetException(Code code) {
        super(code.message);
        this.code = code;
    }

    @Getter
    public enum Code {
        NOT_FOUND("001", HttpStatus.NOT_FOUND, "조회할 브랜드 이미지 또는 게시본이 없습니다."),
        CONFLICT("020", HttpStatus.CONFLICT, "브랜드 설정이 변경되었습니다. 다시 조회해 주세요."),
        STORAGE("030", HttpStatus.SERVICE_UNAVAILABLE, "브랜드 이미지 저장소를 사용할 수 없습니다."),
        INVALID_REQUEST("100", HttpStatus.BAD_REQUEST, "브랜드 요청 형식을 확인해 주세요."),
        VERSION("101", HttpStatus.BAD_REQUEST, "올바른 버전을 입력해 주세요."),
        ALT_TEXT("102", HttpStatus.BAD_REQUEST, "브랜드 이미지의 대체 텍스트를 확인해 주세요."),
        ASSET("103", HttpStatus.BAD_REQUEST, "이 종류로 업로드한 브랜드 이미지를 지정해 주세요."),
        SIZE("104", HttpStatus.CONTENT_TOO_LARGE, "브랜드 이미지는 1 MiB 이하여야 합니다."),
        MIME("105", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "image/png 형식으로 올려 주세요."),
        IMAGE("106", HttpStatus.UNPROCESSABLE_CONTENT, "PNG 이미지의 내용, 해상도와 투명 배경을 확인해 주세요.");

        private final String value;
        private final HttpStatus status;
        private final String message;

        Code(String number, HttpStatus status, String message) {
            this.value = "STORE_BRAND_" + number;
            this.status = status;
            this.message = message;
        }
    }
}
