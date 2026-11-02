package com.commercefoundry.ai.client;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 모델이 요청한 도구 호출 하나. ai-core 는 실행하지 않고 돌려준다 — 실행 · 권한 검증(이 고객의 주문인가 등)은 호출자 몫이다.
 * 인자는 <b>신뢰할 수 없는 모델 출력</b>이다. ai-core 는 스키마 최소 검증(객체 · required · 닫힌 속성 · 최상위 타입, {@link AiTool})만 하고 나머지는 호출자가 본다.
 * 모델이 낸 식별자(주문 id 등)는 믿지 않는다 — 세션 · JWT 의 주체가 그 리소스의 주인인지 실행 전에 확인하고, 아니면 "없음"(404 와 같은 결과)으로 돌려준다
 * (존재 여부를 흘리지 않는다).
 *
 * @param id            호출 ID — 결과({@link AiContent.ToolResult#callId()})와 짝을 맞춘다. 영문 · 숫자 · {@code _} · {@code -}, 1~128자
 * @param name          도구 이름. 로그에 남는다
 * @param argumentsJson 인자 JSON 객체(문자열). 로그 · 예외에 싣지 않는다. 이력으로 다시 보낼 때 가린다
 */
public record AiToolCall(String id, String name, String argumentsJson) implements AiContent {

    public static final Pattern ID = Pattern.compile("[a-zA-Z0-9_-]{1,128}");

    public AiToolCall {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("도구 호출 id 가 규칙에 맞지 않습니다");
        }
        if (name == null || !AiTool.NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("도구 호출 이름이 규칙에 맞지 않습니다");
        }
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        if (AiJson.object(argumentsJson).isEmpty()) {
            // 값을 싣지 않는다 — 인자는 모델 출력이고 개인정보를 품을 수 있다
            throw new IllegalArgumentException("도구 호출 인자는 JSON 객체여야 합니다");
        }
    }

    @Override
    public String toString() {
        return "AiToolCall[id=" + id + ", name=" + name + ", argumentsLength=" + argumentsJson.length() + "]";
    }
}
