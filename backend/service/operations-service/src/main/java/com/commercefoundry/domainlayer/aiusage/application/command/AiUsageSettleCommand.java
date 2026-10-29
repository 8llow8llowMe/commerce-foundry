package com.commercefoundry.domainlayer.aiusage.application.command;

import com.commercefoundry.domainlayer.aiusage.domain.model.AiTokenUsage;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageStatus;
import java.util.Objects;

/**
 * 결과 정산 — {@code AiUsageMeterUseCase#settle} 의 입력. 계량 어댑터(PR-B3)가 ai-core {@code AiCallOutcome} 을 옮겨 담는다.
 *
 * @param callId     예약이 돌려준 원장 호출 아이디
 * @param outcome    결과 구분 — COMPLETED · UNUSABLE · FAILED · TIMEOUT · NOT_SENT
 * @param usage      실측 토큰. COMPLETED · UNUSABLE 에만 있고 나머지는 {@code null} 이어도 된다(무시한다)
 * @param model      답한 모델. 없으면 {@code null}(요청한 모델로 계산한다)
 * @param reason     실패 원인 이름(ai-core {@code AiException.Reason}). 없으면 {@code null}
 * @param httpStatus API 오류 응답의 상태 코드. 없으면 {@code null}
 * @param messageId  API 메시지 ID. 없으면 {@code null}
 * @param elapsedMs  걸린 시간
 */
public record AiUsageSettleCommand(
    long callId, AiUsageStatus outcome, AiTokenUsage usage, String model, String reason, Integer httpStatus, String messageId, long elapsedMs
) {

    public AiUsageSettleCommand {
        Objects.requireNonNull(outcome, "outcome");
        if (!outcome.isOutcome()) {
            throw new IllegalArgumentException("호출 결과가 아닌 상태입니다: " + outcome);
        }
        if (outcome.chargesActualUsage() && usage == null) {
            throw new IllegalArgumentException(outcome + " 에는 사용량이 있어야 합니다.");
        }
    }
}
