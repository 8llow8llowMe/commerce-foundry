package com.commercefoundry.domainlayer.order.application.model;

import com.commercefoundry.domainlayer.order.domain.model.Claim;
import java.util.Objects;

/**
 * 결제 후 취소 클레임의 원격 단계(환불 · 재고 복원) 결과 (#16 PR-2) — 원격 응답 · 예외를 이 값으로만 바꿔 트랜잭션 단계에 넘긴다(원격 본문 · cause 를 싣지 않는다).
 *
 * <ul>
 *   <li>{@link Kind#DONE} — 확정(같은 클레임의 기존 성공 포함)</li>
 *   <li>{@link Kind#FAILED} — 종결 거절. 다시 보내도 같다. {@code failureCode} 는 원격 오류 코드</li>
 *   <li>{@link Kind#RETRY} — 미확정. 같은 클레임 아이디 · 같은 본문으로 나중에 다시 보낸다</li>
 * </ul>
 *
 * @param failureCode FAILED 면 {@link Claim#FAILURE_CODE_MAX_LENGTH} 이하 코드, 그 밖에는 null
 */
public record CancellationStepOutcome(Kind kind, String failureCode) {

    public CancellationStepOutcome {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.FAILED) != (failureCode != null)
            || (failureCode != null && (failureCode.isBlank() || failureCode.length() > Claim.FAILURE_CODE_MAX_LENGTH))) {
            throw new IllegalArgumentException("failureCode is required only for FAILED and must be 1~40 chars");
        }
    }

    public static CancellationStepOutcome done() {
        return new CancellationStepOutcome(Kind.DONE, null);
    }

    public static CancellationStepOutcome failed(String failureCode) {
        return new CancellationStepOutcome(Kind.FAILED, failureCode);
    }

    public static CancellationStepOutcome retry() {
        return new CancellationStepOutcome(Kind.RETRY, null);
    }

    public enum Kind {
        DONE, FAILED, RETRY
    }
}
