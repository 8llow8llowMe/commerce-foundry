package com.commercefoundry.domainlayer.order.application.model;

import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import java.util.Objects;

/**
 * 주문 요청 기록을 보고 · 잡은 결과 ({@code OrderRequestProcessor}).
 *
 * @param outcome 아래 넷 중 하나
 * @param request 기록. {@link Outcome#NONE} 이면 null
 */
public record OrderRequestClaim(Outcome outcome, OrderRequest request) {

    public OrderRequestClaim {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.NONE && request != null) {
            throw new IllegalArgumentException("NONE must not contain a request");
        }
        if (outcome != Outcome.NONE) {
            Objects.requireNonNull(request, "request");
        }
    }

    public static OrderRequestClaim none() {
        return new OrderRequestClaim(Outcome.NONE, null);
    }

    public boolean isReplay() {
        return outcome == Outcome.REPLAY;
    }

    /** 주문 아이디 — {@link Outcome#NONE} 에는 없다. */
    public long orderId() {
        return request.orderId();
    }

    public enum Outcome {

        /** 기록이 없다 — 계산 뒤 새로 넣는다. */
        NONE,
        /** 같은 본문의 기록이 lease 가 끝난 처리 중이다 — 계산 뒤 이어받는다. */
        TAKE_OVER,
        /** 이 시도가 기록을 잡았다(새로 넣었거나 이어받았다) — 재고를 선점하고 주문을 저장한다. 기록의 버전으로 완료한다. */
        ACQUIRED,
        /** 같은 본문의 주문이 이미 저장됐다 — 그 주문을 다시 읽어 돌려준다. */
        REPLAY
    }
}
