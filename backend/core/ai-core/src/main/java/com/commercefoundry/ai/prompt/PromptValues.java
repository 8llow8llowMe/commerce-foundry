package com.commercefoundry.ai.prompt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 템플릿 자리에 넣을 값. 개인정보 자리({@code {{pii:이름}}})는 {@link #putSensitive} 로만, 일반 자리({@code {{이름}}})는 {@link #put} 으로만 채운다 —
 * 섞어 넣으면 렌더링이 실패한다. 개인정보 자리에는 렌더링 때 값 대신 {@code [PII]} 가 들어가고, 값은 요청의 {@code sensitiveValues} 로만 넘어가
 * 다른 자리에 같은 값이 섞여 나오면 보내기 전에 가려진다.
 *
 * <p>일반 값은 기본 정규식 패턴(이메일 · 전화 · 주소 등)으로만 가려진다 — 고객 자유 텍스트 속 이름 · 형식 없는 주소는 잡히지 않을 수 있다.
 */
public final class PromptValues {

    private final Map<String, String> plain = new LinkedHashMap<>();
    private final Map<String, String> sensitive = new LinkedHashMap<>();

    public static PromptValues create() {
        return new PromptValues();
    }

    /** 일반 값(치수 · 상품명 · 집계 수치 등). 개인정보가 섞일 수 있는 자유 텍스트도 기본 패턴으로 가려지지만, 아는 개인정보는 {@link #putSensitive} 로 넣는다. */
    public PromptValues put(String name, Object value) {
        plain.put(Objects.requireNonNull(name, "name"), String.valueOf(Objects.requireNonNull(value, "value")));
        return this;
    }

    /** 개인정보 값(받는 사람 이름 · 주소 · 연락처 · 주문번호 등) — 템플릿의 {@code {{pii:이름}}} 자리에만 들어간다. */
    public PromptValues putSensitive(String name, String value) {
        sensitive.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
        return this;
    }

    Map<String, String> plain() {
        return Collections.unmodifiableMap(plain);
    }

    Map<String, String> sensitive() {
        return Collections.unmodifiableMap(sensitive);
    }

    /** 값을 로그로 내지 않는다 — 이름만 싣는다. */
    @Override
    public String toString() {
        return "PromptValues[plain=" + plain.keySet() + ", sensitive=" + sensitive.keySet() + "]";
    }
}
