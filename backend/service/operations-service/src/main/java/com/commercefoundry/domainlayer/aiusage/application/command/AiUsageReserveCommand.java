package com.commercefoundry.domainlayer.aiusage.application.command;

import java.util.Objects;

/**
 * 보내기 전 예약 — {@code AiUsageMeterUseCase#reserve} 의 입력. 계량 어댑터(PR-B3)가 ai-core {@code AiCallContext} 를 옮겨 담는다. 원문은 없다.
 *
 * @param feature        기능 이름 (63자 이하)
 * @param promptVersion  템플릿 버전. 없으면 {@code null}
 * @param callerRef      호출한 작업의 식별자({@code job:123}, 100자 이하). 없으면 {@code null}
 * @param requestedModel 요청한 모델 ID — 예상 비용의 단가
 * @param maxTokens      최대 출력 토큰 — 예상 비용의 출력 몫
 * @param inputChars     마스킹 뒤 입력 글자 수 — 예상 비용의 입력 몫
 * @param imageCount     이미지 장수
 * @param structured     구조화 출력 호출이면 {@code true}
 */
public record AiUsageReserveCommand(
    String feature, Integer promptVersion, String callerRef, String requestedModel, int maxTokens, long inputChars, int imageCount, boolean structured
) {

    public AiUsageReserveCommand {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(requestedModel, "requestedModel");
    }
}
