package com.commercefoundry.domainlayer.payment.adapter.out.client.feign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** 7개 응답 칸만 읽는다. 누락된 칸은 기본값으로 채우지 않고 support 에서 검증한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPaymentResultInternalResponse(
    @JsonDeserialize(using = StrictString.class) String paymentId,
    @JsonDeserialize(using = StrictString.class) String orderId,
    @JsonDeserialize(using = StrictString.class) String memberId,
    String result, Amount amount, Instant approvedAt,
    @JsonDeserialize(using = StrictString.class) String stockReservationId
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Amount(@JsonDeserialize(using = StrictLong.class) Long amount, String currency) {
    }

    /** 숫자 JSON 을 문자열 식별자로 강제 변환하지 않는다. */
    public static final class StrictString extends ValueDeserializer<String> {

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw new IllegalArgumentException("String token required");
            }
            return parser.getString();
        }
    }

    /** 소수 · 문자열 금액을 long 으로 자르거나 강제 변환하지 않는다. */
    public static final class StrictLong extends ValueDeserializer<Long> {

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                throw new IllegalArgumentException("Integer token required");
            }
            return parser.getLongValue();
        }
    }
}
