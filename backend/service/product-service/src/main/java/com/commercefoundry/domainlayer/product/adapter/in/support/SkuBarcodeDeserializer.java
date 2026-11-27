package com.commercefoundry.domainlayer.product.adapter.in.support;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 바코드는 문자열로 받아 앞자리 0과 대소문자를 그대로 보존한다. */
public class SkuBarcodeDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return context.reportInputMismatch(String.class, "바코드는 문자열로 입력해 주세요.");
        }
        return parser.getString();
    }
}
