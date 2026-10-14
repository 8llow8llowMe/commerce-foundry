package com.commercefoundry.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "공통 응답 래퍼")
public record Response<T>(

    @Schema(description = "응답 헤더")
    DataHeader dataHeader,

    @Schema(description = "응답 본문. 실패면 null")
    T dataBody
) {

    public static <T> Response<T> success(T dataBody) {
        return new Response<>(DataHeader.ok(), dataBody);
    }

    public static Response<Void> success() {
        return new Response<>(DataHeader.ok(), null);
    }

    public static <T> Response<T> fail(String resultCode, String resultMessage) {
        return new Response<>(DataHeader.error(resultCode, resultMessage), null);
    }

    /** 필드 단위 검증 오류를 함께 싣는다. {@code resultMessage} 는 대표 오류의 메시지 문자열이다. */
    public static <T> Response<T> fail(String resultCode, String resultMessage, List<ValidationErrorItem> fieldErrors) {
        return new Response<>(DataHeader.error(resultCode, resultMessage, fieldErrors), null);
    }
}
