package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockReservationResponse;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockRestorationResponse;
import com.commercefoundry.domainlayer.product.adapter.in.internal.presenter.StockInternalPresenter;
import com.commercefoundry.domainlayer.product.application.command.StockReservationCommand;
import com.commercefoundry.domainlayer.product.application.command.StockRestorationCommand;
import com.commercefoundry.domainlayer.product.application.port.in.StockInternalUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.StockReservationProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.StockRestorationProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 I/O 가 없어 Facade 에 트랜잭션을 건다 — 선점 · 복원 기록과 재고 차감 · 복원이 함께 커밋되거나 함께 롤백된다.
 *
 * <p>격리 수준은 기본(READ COMMITTED)이다. 초과 판매는 조건부 UPDATE 가, 동시 상태 변경은 {@code @Version} 이 막으므로 더 높은 격리가 필요 없다.
 * 결제 후 복원은 상태 전이가 없어 선점 헤더 비관적 잠금으로 같은 선점의 복원을 줄 세운다 ({@code StockRestorationProcessor}).
 */
@Service
@RequiredArgsConstructor
public class StockInternalFacade implements StockInternalUseCase {

    private final StockReservationProcessor stockReservationProcessor;
    private final StockRestorationProcessor stockRestorationProcessor;
    private final StockInternalPresenter stockInternalPresenter;

    @Override
    @Transactional
    public StockReservationResponse reserve(StockReservationCommand command) {
        return stockInternalPresenter.toResponse(stockReservationProcessor.reserve(command));
    }

    @Override
    @Transactional
    public StockReservationResponse confirm(long reservationId) {
        return stockInternalPresenter.toResponse(stockReservationProcessor.confirm(reservationId));
    }

    @Override
    @Transactional
    public StockReservationResponse release(long reservationId) {
        return stockInternalPresenter.toResponse(stockReservationProcessor.release(reservationId));
    }

    @Override
    @Transactional
    public StockRestorationResponse restore(StockRestorationCommand command) {
        return stockInternalPresenter.toResponse(stockRestorationProcessor.restore(command));
    }
}
