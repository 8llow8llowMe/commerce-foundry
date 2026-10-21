package com.commercefoundry.domainlayer.order.adapter.out.client.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import feign.FeignException;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서비스 간 4xx 오류 봉투의 {@code resultCode} 읽기 · 로그 정리 — 이 패키지의 {@code *InternalResponseSupport} 가 같이 쓴다 ({@code coding-conventions.md} §10).
 * support 끼리 서로의 static 메서드를 부르지 않게 공용 부분을 여기 둔다. 패키지 밖에서는 쓰지 않는다.
 */
final class InternalResultCodes {

    static final String UNRECOGNIZED = "<unrecognized>";

    /**
     * 로그에 그대로 남기는 resultCode 의 모양 — common-core {@code ValidationErrorSupport} 의 코드 판정({@code DOMAIN_NNN})과 같다.
     * 그쪽 판정은 private 이라 가져다 쓰지 않고 같은 식을 둔다.
     */
    private static final Pattern RESULT_CODE_SHAPE = Pattern.compile("[A-Z][A-Z0-9_]*_[0-9]{3}");
    private static final int MAX_LOGGED_LENGTH = 40;

    private InternalResultCodes() {
    }

    /**
     * 원격이 보낸 resultCode 를 로그에 넣을 값으로 바꾼다. 코드 모양({@code [A-Z][A-Z0-9_]*_[0-9]{3}}, 40자 이하)일 때만 그대로이고,
     * 아니면(CR/LF · 긴 값 · 임의 문자열) {@value #UNRECOGNIZED} 다. 코드가 없으면(본문 없음 · JSON 아님) null 그대로다 — 원격 문자열이 아니다.
     */
    static String loggable(String resultCode) {
        if (resultCode == null) {
            return null;
        }
        return resultCode.length() <= MAX_LOGGED_LENGTH && RESULT_CODE_SHAPE.matcher(resultCode).matches() ? resultCode : UNRECOGNIZED;
    }

    /** 오류 봉투의 {@code dataHeader.resultCode}. 본문이 없거나 JSON 이 아니거나 코드가 없으면 null 이다. 봉투는 UTF-8 JSON 이다. 다른 칸(메시지)은 읽지 않는다. */
    static String resultCodeOf(FeignException exception, JsonMapper jsonMapper) {
        String body = exception.contentUTF8();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            ErrorEnvelope envelope = jsonMapper.readValue(body, ErrorEnvelope.class);
            return envelope == null || envelope.dataHeader() == null ? null : envelope.dataHeader().resultCode();
        } catch (JacksonException parseFailure) {
            return null;
        }
    }

    /** 4xx 오류 봉투에서 읽는 칸 — {@code dataHeader.resultCode} 하나다. 나머지 칸은 무시한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorEnvelope(ErrorHeader dataHeader) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorHeader(String resultCode) {
    }
}
