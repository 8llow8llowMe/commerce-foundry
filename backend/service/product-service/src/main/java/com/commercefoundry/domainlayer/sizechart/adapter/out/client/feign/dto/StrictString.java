package com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Prevent numeric JSON IDs and other scalars from being coerced into string fields. */
public final class StrictString extends ValueDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            throw new IllegalArgumentException("String token required");
        }
        return parser.getString();
    }
}
