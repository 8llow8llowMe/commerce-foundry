package com.commercefoundry.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 모델에 넘기는 도구 정의 하나. 서버가 정한 도구만 넘긴다 — 고객 입력으로 도구를 만들지 않는다. ai-core 는 도구를 <b>실행하지 않는다</b>(호출자 책임).
 *
 * <p><b>보안 규칙</b> ({@code bootstrap-conventions.md} AI):
 * <ul>
 *   <li>주체(memberId · 세션)는 스키마에 두지 않는다 — 실행할 때 세션 · JWT 에서만 꺼낸다. 모델이 고른 값이 "누구의" 데이터인지 정하면 안 된다.</li>
 *   <li>{@code "additionalProperties": false} 를 기본으로 둔다 — 모르는 키가 오면 ai-core 가 거절한다.</li>
 *   <li>도구 결과에는 답에 필요한 최소 필드만 담는다. 결과 안의 고객 작성 텍스트(배송 메모 · 문의 본문)는 프롬프트 주입 경로다.
 *       결과에 섞이는 개인정보 값은 요청의 {@code sensitiveValues} 로 넘긴다.</li>
 * </ul>
 *
 * @param name             도구 이름 — 영문 · 숫자 · {@code _} · {@code -}, 1~64자(API 규칙). 로그에 남는다
 * @param description      모델이 언제 이 도구를 쓸지 고르는 설명. 비울 수 없다
 * @param inputSchemaJson  인자의 JSON 스키마(문자열). 최상위가 {@code "type": "object"} 인 객체여야 한다
 */
public record AiTool(String name, String description, String inputSchemaJson) {

    /** 도구 이름 규칙 — Messages API 와 OpenAI 함수 이름 규칙의 교집합이다. */
    public static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    public AiTool {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("도구 이름은 영문 · 숫자 · '_' · '-' 로 1~64자여야 합니다");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("도구 설명이 비었습니다: " + name);
        }
        ObjectNode schema = AiJson.object(inputSchemaJson)
            .orElseThrow(() -> new IllegalArgumentException("도구 스키마가 JSON 객체가 아닙니다: " + name));
        if (!"object".equals(schema.path("type").asText(null))) {
            throw new IllegalArgumentException("도구 스키마의 최상위 type 은 object 여야 합니다: " + name);
        }
        JsonNode required = schema.get("required");
        if (required != null && (!required.isArray() || !allText(required))) {
            throw new IllegalArgumentException("도구 스키마의 required 는 문자열 배열이어야 합니다: " + name);
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && !properties.isObject()) {
            throw new IllegalArgumentException("도구 스키마의 properties 는 객체여야 합니다: " + name);
        }
    }

    /** 스키마 · 설명 · 이름 글자 수 — 입력 상한 · 예상 비용에 더한다(도구 정의도 입력 토큰이다). */
    long length() {
        return name.length() + description.length() + inputSchemaJson.length();
    }

    /**
     * 모델이 낸 인자가 이 스키마에 맞는지 <b>최소한</b>으로 본다 — JSON 객체인가 · {@code required} 키가 다 있는가 ·
     * {@code additionalProperties: false} 면 모르는 키가 없는가 · 최상위 속성의 {@code type}(문자열 하나일 때)이 맞는가.
     * 중첩 객체 · {@code enum} · 길이 · 패턴 · {@code anyOf} 같은 나머지 JSON Schema 규칙은 보지 않는다 — 호출자가 실행 전에 업무 검증을 한다.
     *
     * @return 맞지 않으면 원인 구분(값 없음 — 로그에 남겨도 된다)
     */
    Optional<String> argumentProblem(String argumentsJson) {
        Optional<ObjectNode> parsed = AiJson.object(argumentsJson);
        if (parsed.isEmpty()) {
            return Optional.of("not a json object");
        }
        ObjectNode arguments = parsed.get();
        ObjectNode schema = AiJson.object(inputSchemaJson).orElseThrow();
        JsonNode required = schema.get("required");
        if (required != null) {
            for (JsonNode key : required) {
                if (!arguments.has(key.asText())) {
                    return Optional.of("missing required");
                }
            }
        }
        JsonNode properties = schema.path("properties");
        boolean closed = schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean();
        Iterator<Map.Entry<String, JsonNode>> fields = arguments.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode property = properties.get(field.getKey());
            if (property == null) {
                if (closed) {
                    return Optional.of("unknown property");
                }
                continue;
            }
            JsonNode type = property.get("type");
            if (type != null && type.isTextual() && !matches(type.asText(), field.getValue())) {
                return Optional.of("type mismatch");
            }
        }
        return Optional.empty();
    }

    private static boolean matches(String type, JsonNode value) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber() || (value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0);
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private static boolean allText(JsonNode array) {
        for (JsonNode node : array) {
            if (!node.isTextual()) {
                return false;
            }
        }
        return true;
    }

    /** 스키마 · 설명은 서버 문구라 원문을 실어도 되지만, 길이만 싣는다(로그 크기). */
    @Override
    public String toString() {
        return "AiTool[name=" + name + ", descriptionLength=" + description.length() + ", schemaLength=" + inputSchemaJson.length() + "]";
    }
}
