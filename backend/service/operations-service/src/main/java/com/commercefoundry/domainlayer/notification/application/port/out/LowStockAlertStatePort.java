package com.commercefoundry.domainlayer.notification.application.port.out;

import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;
import java.util.List;
import java.util.Optional;

/**
 * 품절 임박 점검 상태 저장(#229). 모두 호출자 트랜잭션 안에서만 동작한다.
 *
 * <p>{@link #findLocked()} 가 워터마크 단일 행을 NOWAIT 로 잠근 뒤에만 나머지를 부른다 — 그 잠금이 추적 행 쓰기까지 직렬화한다.
 * 빈 조회 뒤에는 {@link #insertInitial} 만 부르고 경쟁 INSERT 실패를 같은 트랜잭션에서 복구하지 않는다.
 */
public interface LowStockAlertStatePort {
    Optional<LowStockAlertPolicy.State> findLocked();

    /** 추적 중인 SKU 전체. 품절 임박 기준을 설정한 SKU 수로 제한된다. */
    List<LowStockAlertPolicy.Tracked> findAllTracked();

    void insertInitial(LowStockAlertPolicy.State state);

    void updateLocked(LowStockAlertPolicy.State state);

    void applySkuChanges(LowStockAlertPolicy.Changes changes);
}
