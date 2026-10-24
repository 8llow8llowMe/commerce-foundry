package com.commercefoundry.ai.client;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 호출 한 번의 입력. 기능 쪽은 보통 {@code PromptTemplate.render} 로 만든다.
 *
 * @param feature         기능 이름({@code size-chart} 등). 모델 · 토큰 설정을 고르고 로그 · 사용량을 나누는 키다
 * @param system          시스템 프롬프트. 없으면 {@code null}
 * @param prompt          사용자 메시지
 * @param sensitiveValues 호출자가 아는 개인정보 값(받는 사람 이름 · 주소 · 연락처 · 주문번호 등). 보내기 전에 가린다 —
 *                        이름처럼 정규식으로 잡을 수 없는 값은 여기로 넘긴다. 두 글자 미만은 무시한다(한 글자를 가리면 본문이 깨진다)
 * @param promptVersion   이 요청을 만든 템플릿 버전. 템플릿 없이 만들었으면 {@code null}. 로그에 남겨 결과를 버전과 대조한다
 */
public record AiRequest(String feature, String system, String prompt, List<String> sensitiveValues, Integer promptVersion) {

    /** 기능 이름 규칙 — 소문자 · 숫자 · {@code -}, 1~63자. 템플릿 경로에도 들어가므로 한 곳에서 정한다. */
    public static final Pattern FEATURE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    public AiRequest {
        Objects.requireNonNull(feature, "feature");
        if (!FEATURE_NAME.matcher(feature).matches()) {
            throw new IllegalArgumentException("feature 는 소문자 · 숫자 · '-' 로 1~63자여야 합니다");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 가 비었습니다");
        }
        sensitiveValues = sensitiveValues == null ? List.of() : List.copyOf(sensitiveValues.stream().filter(Objects::nonNull).toList());
    }

    /** 템플릿 없이 만든 요청. */
    public AiRequest(String feature, String system, String prompt, List<String> sensitiveValues) {
        this(feature, system, prompt, sensitiveValues, null);
    }

    public static AiRequest of(String feature, String system, String prompt) {
        return new AiRequest(feature, system, prompt, List.of(), null);
    }

    /** 원문(프롬프트 · 개인정보 값)을 로그로 내지 않는다 — 길이와 개수만 싣는다. */
    @Override
    public String toString() {
        return "AiRequest[feature=" + feature + ", promptVersion=" + promptVersion + ", systemLength=" + (system == null ? 0 : system.length())
            + ", promptLength=" + prompt.length() + ", sensitiveValues=" + sensitiveValues.size() + "]";
    }
}
