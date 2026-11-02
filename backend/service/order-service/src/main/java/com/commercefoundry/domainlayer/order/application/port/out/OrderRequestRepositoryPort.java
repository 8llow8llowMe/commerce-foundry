package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.application.port.out.query.PendingProductQuantityQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequestItem;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 요청 원문 없이 회원·키·지문을 저장한다. 획득/인수/기한 종료는 각각 독립된 짧은 트랜잭션이다. */
public interface OrderRequestRepositoryPort {

    /**
     * 새 기록과 그 상품별 수량(#436)을 한 트랜잭션에 넣는다. 같은 회원 · 같은 키의 기록이 이미 있으면 둘 다 넣지 않고 비어 있다.
     *
     * @param items 상품마다 한 줄, 비어 있지 않다 ({@link OrderRequestItem#of})
     */
    Optional<OrderRequest> insertIfAbsent(OrderRequest request, List<OrderRequestItem> items);
    Optional<OrderRequest> findByMemberIdAndIdempotencyKey(long memberId, UUID key);

    /**
     * 회원의 요청 기록 중 마지막 활동(삽입 · 인수 · lease 종료 · 완료 — {@code updated_at})이 {@code since} 이후(포함)인 것의 수, {@code excludedRequestId} 는 뺀다 (#231 활성 시도 상한).
     * 상태와 무관하다. 만든 시각이 아니라 활동 시각으로 세야 창 밖 휴면 기록을 이어받은 시도도 창에 다시 들어온다. 뺄 기록이 없으면 0.
     */
    long countActiveSince(long memberId, Instant since, long excludedRequestId);

    /**
     * 회원의 진행 중 기록에 담긴 상품별 수량 합 (#436) — 처리 중이고 lease 가 {@code now} 뒤에 끝나며 마지막 활동이 {@code since} 이후(포함)인 기록만,
     * {@code excludedRequestId} 는 뺀다. 완료된 기록은 주문(결제 대기)으로 세므로 여기서 빠진다. 항목이 없는 기록(배포 전)은 0 이다.
     *
     * @return 수량이 있는 상품만. {@code productIds} 가 비어 있으면 빈 목록
     */
    List<PendingProductQuantityQueryResult> sumInFlightQuantitiesByProduct(
        long memberId, Collection<Long> productIds, long excludedRequestId, Instant now, Instant since
    );
    Optional<OrderRequest> takeOver(OrderRequest expected, Instant now, Duration lease);
    boolean endLease(OrderRequest expected, Instant now);

    /**
     * 기존 쓰기 트랜잭션 필수. 기한·버전·회원·키·지문·주문 ID가 일치하는 주인만 완료한다.
     * 후속 C3는 이를 먼저 호출하고 C1 주문 저장을 같은 트랜잭션에서 수행한다. 실패 시 전체 롤백한다.
     *
     * <p>lease 가 {@code now + margin} 뒤에 끝나야 완료한다 (#436) — 완료 커밋이 lease 끝보다 앞서게 해, 다른 시도의 수량 계수가 이 시도를
     * "lease 끝남" 으로 뺄 때는 주문이 이미 커밋돼 보이게 한다. margin 안이면 다른 시도에 인수당한 것과 같이 거절한다.
     *
     * @param margin {@code order.creation.completion-margin} — 0 보다 크다
     */
    void completeOwned(OrderRequest expected, Instant now, Duration margin);
}
