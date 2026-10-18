package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockReservationItemRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockReservationRepository;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.mapper.StockReservationMapper;
import com.commercefoundry.domainlayer.product.application.port.out.StockReservationRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.StockReservation;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 쓰기는 모두 바로 flush 한다 — 유니크 키 위반 · 버전 충돌을 이 자리에서 드러내 도메인 409 로 바꾼다.
 * 커밋 시점까지 미루면 트랜잭션 밖에서 터져 {@code ProductException} 으로 바꿀 수 없다.
 */
@Component
@RequiredArgsConstructor
public class StockReservationRepositoryAdapter implements StockReservationRepositoryPort {

    private final StockReservationRepository stockReservationRepository;
    private final StockReservationItemRepository stockReservationItemRepository;
    private final StockReservationMapper stockReservationMapper;

    @Override
    public Optional<StockReservation> findById(long reservationId) {
        return stockReservationRepository.findById(reservationId).map(this::withItems);
    }

    @Override
    public Optional<StockReservation> findActiveByOrderId(long orderId) {
        return stockReservationRepository.findByActiveOrderId(orderId).map(this::withItems);
    }

    @Override
    public List<Long> findExpiredReservationIds(Instant now, int limit) {
        return stockReservationRepository.findIdsByStatusAndExpiresAtUpTo(StockReservationStatus.RESERVED, now, Limit.of(limit));
    }

    /**
     * 헤더(버전 null → persist)와 항목(Persistable → persist)을 INSERT 한다. 입력이 검증 · 병합을 거쳐 항목 쪽 제약은 위반되지 않으므로
     * 여기서 나는 무결성 위반은 같은 주문의 활성 선점 유니크 키({@code uk_stock_reservation_active_order_id}) 충돌뿐이다.
     */
    @Override
    public void create(StockReservation reservation) {
        try {
            stockReservationRepository.saveAndFlush(stockReservationMapper.toEntity(reservation));
            stockReservationItemRepository.saveAllAndFlush(stockReservationMapper.toItemEntities(reservation));
        } catch (DataIntegrityViolationException exception) {
            throw new ProductException(ProductErrorCode.STOCK_RESERVATION_CONFLICT, exception);
        }
    }

    /**
     * 버전이 있는 헤더를 merge 한다. 읽은 뒤 다른 트랜잭션이 커밋했으면 merge(버전 비교) 또는 flush 의 {@code UPDATE ... where version = ?} 가 0행이 되어
     * {@link ObjectOptimisticLockingFailureException} 이 난다.
     */
    @Override
    public void update(StockReservation reservation) {
        if (reservation.version() == null) {
            throw new IllegalArgumentException("저장하지 않은 선점은 갱신할 수 없습니다. reservationId=" + reservation.id());
        }
        try {
            stockReservationRepository.saveAndFlush(stockReservationMapper.toEntity(reservation));
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new ProductException(ProductErrorCode.STOCK_RESERVATION_CONFLICT, exception);
        }
    }

    private StockReservation withItems(StockReservationEntity entity) {
        return stockReservationMapper.toDomain(entity, stockReservationItemRepository.findAllByReservationIdOrderBySkuIdAsc(entity.getId()));
    }
}
