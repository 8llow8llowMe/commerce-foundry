package com.commercefoundry.ai.client;

import java.util.List;

/**
 * 구조화 결과의 업무 검증. 스키마(타입 · 필수 필드)는 API 가 맞춰 주지만 값의 뜻은 보장하지 않는다 — 치수가 음수이거나, 허용하지 않은 분류이거나,
 * 합계가 맞지 않을 수 있다. 그런 결과를 쓰지 않도록 서비스가 규칙을 준다.
 */
@FunctionalInterface
public interface AiOutputValidator<T> {

    /** @return 문제 설명 목록. 비었으면 통과다. 설명은 로그에 남지 않으므로(개수만) 디버깅용으로 자유롭게 쓴다 */
    List<String> validate(T value);

    static <T> AiOutputValidator<T> none() {
        return value -> List.of();
    }
}
