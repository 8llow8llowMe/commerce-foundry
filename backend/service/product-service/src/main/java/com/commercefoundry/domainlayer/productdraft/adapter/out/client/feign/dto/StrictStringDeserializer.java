package com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 숫자 · 불리언 같은 다른 스칼라를 문자열 칸으로 바꿔 받지 않는다(선례: 사이즈표 {@code StrictString}). 실패 메시지에 원문을 싣지 않는다. */
public final class StrictStringDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            throw new IllegalArgumentException("문자열 토큰이어야 합니다.");
        }
        return parser.getString();
    }
}
