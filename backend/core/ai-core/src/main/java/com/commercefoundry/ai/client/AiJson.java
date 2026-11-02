package com.commercefoundry.ai.client;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

/**
 * 도구 스키마 · 인자 · 결과 JSON 을 읽는 엄격한 파서. 뒤에 남은 토큰 · 중복 키를 거절한다 — 같은 키가 두 번이면 어느 값을 볼지 모호해진다.
 * 예외 메시지는 입력 조각을 품으므로 밖으로 내보내지 않는다(파싱 실패는 {@link Optional#empty()}).
 * 읽기 상한을 명시한다 — 중첩 깊이 {@value #MAX_DEPTH}(마스킹 · 검증이 재귀라 스택을 묶는다), 문자열 · 문서 길이({@value #MAX_DOCUMENT_CHARS}자).
 * 입력 전체는 {@code ai.max-input-chars} 가 따로 묶는다.
 */
final class AiJson {

    static final int MAX_DEPTH = 64;
    static final int MAX_DOCUMENT_CHARS = 1_000_000;

    static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(MAX_DEPTH)
                .maxStringLength(MAX_DOCUMENT_CHARS)
                .maxDocumentLength(MAX_DOCUMENT_CHARS)
                .build())
            .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .build();

    private AiJson() {
    }

    /** JSON 객체로 읽는다. 객체가 아니거나 형식이 틀리면 비어 있다. */
    static Optional<ObjectNode> object(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node instanceof ObjectNode object ? Optional.of(object) : Optional.empty();
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // 트리를 쓰는 데는 실패하지 않는다 — 원인(값 조각)은 잇지 않는다
            throw new IllegalStateException("JSON 을 쓰지 못했습니다");
        }
    }
}
