package com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 영수증 회차는 JSON 정수 토큰만 받는다. 소수 · 문자열 강제 변환을 막는다. */
public final class StrictIntegerDeserializer extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw new IllegalArgumentException("정수 토큰이어야 합니다.");
        }
        return parser.getIntValue();
    }
}
