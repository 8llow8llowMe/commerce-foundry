package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import java.util.Optional;
import java.util.UUID;

/**
 * 주문 요청 기록 ({@code orders.order_request}). 회원 × 키 하나에 한 행이고, DB 유니크 키가 동시에 잡는 시도 중 하나만 통과시킨다.
 * 리포지토리 호출마다 바로 flush 한다 — 바깥 트랜잭션이 없으면 호출 하나가 한 트랜잭션이다.
 */
public interface OrderRequestRepositoryPort {

    /**
     * 새 기록을 넣는다.
     *
     * @return 저장된 행(버전 0). 같은 회원 · 같은 키가 이미 있으면(유니크 키 위반) 빈 값 — 실패한 넣기의 트랜잭션은 이미 끝났으므로 부른 쪽이 새로 읽는다
     */
    Optional<OrderRequest> insertIfAbsent(OrderRequest request);

    Optional<OrderRequest> findByMemberIdAndIdempotencyKey(long memberId, UUID idempotencyKey);

    /**
     * 읽어 온 행의 상태 · lease 를 바꿔 저장한다 — 버전이 다르면 거절한다.
     *
     * @return 저장된 행 (버전이 오른다). 다른 시도가 먼저 바꿨으면(@Version) 빈 값
     */
    Optional<OrderRequest> saveIfUnchanged(OrderRequest request);
}
