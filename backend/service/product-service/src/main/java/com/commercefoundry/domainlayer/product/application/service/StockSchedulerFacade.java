package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.product.application.info.ExpiredStockReservationInfo;
import com.commercefoundry.domainlayer.product.application.port.in.StockSchedulerUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.StockReservationProcessor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료 스윕의 트랜잭션 경계. 스케줄러(다른 빈)가 이 Facade 의 프록시를 거쳐 메서드마다 부르므로 {@link #expire} 가 건마다 새 트랜잭션이다 —
 * 같은 클래스 안에서 서로 부르지 않는다(self-invocation 은 프록시를 거치지 않아 트랜잭션이 걸리지 않는다).
 */
@Service
@RequiredArgsConstructor
public class StockSchedulerFacade implements StockSchedulerUseCase {

    private final StockReservationProcessor stockReservationProcessor;

    @Override
    @Transactional(readOnly = true)
    public List<ExpiredStockReservationInfo> findExpiredReservations(ExpiredStockReservationInfo after, int limit) {
        return stockReservationProcessor.findExpiredReservations(after, limit);
    }

    /** 확정과 동시에 오면 {@code @Version} 이 하나만 통과시키고, 진 쪽은 409 로 롤백된다 — 스케줄러가 로그만 남기고 다음 건으로 간다. */
    @Override
    @Transactional
    public boolean expire(long reservationId) {
        return stockReservationProcessor.expire(reservationId);
    }
}
