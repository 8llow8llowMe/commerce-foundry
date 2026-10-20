package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockRestorationEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockRestorationItemRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockRestorationRepository;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.mapper.StockRestorationMapper;
import com.commercefoundry.domainlayer.product.application.port.out.StockRestorationRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuRestoredQuantityQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.StockRestoration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 쓰기는 바로 flush 한다 — 클레임 유니크 키 위반을 이 자리에서 드러내 도메인 409 로 바꾼다 ({@code coding-conventions.md} §9-1).
 */
@Component
@RequiredArgsConstructor
public class StockRestorationRepositoryAdapter implements StockRestorationRepositoryPort {

    private final StockRestorationRepository stockRestorationRepository;
    private final StockRestorationItemRepository stockRestorationItemRepository;
    private final StockRestorationMapper stockRestorationMapper;

    @Override
    public Optional<StockRestoration> findByClaimId(long claimId) {
        return stockRestorationRepository.findByClaimId(claimId).map(this::withItems);
    }

    @Override
    public List<SkuRestoredQuantityQueryResult> sumRestoredQuantitiesByReservationId(long reservationId) {
        return stockRestorationItemRepository.sumQuantitiesByReservationId(reservationId);
    }

    /**
     * 헤더(Persistable → persist)를 먼저 INSERT · flush 한다. 입력이 검증을 거쳐 헤더 쪽 다른 제약은 위반되지 않으므로 여기서 나는 무결성 위반은
     * 같은 클레임의 복원 유니크 키({@code uk_stock_restoration_claim_id}) 충돌뿐이다. 항목은 명령에서 SKU 별로 합쳐져 항목 제약을 위반하지 않는다.
     */
    @Override
    public void create(StockRestoration restoration) {
        try {
            stockRestorationRepository.saveAndFlush(stockRestorationMapper.toEntity(restoration));
        } catch (DataIntegrityViolationException exception) {
            throw new ProductException(ProductErrorCode.STOCK_RESTORATION_CONFLICT, exception);
        }
        stockRestorationItemRepository.saveAllAndFlush(stockRestorationMapper.toItemEntities(restoration));
    }

    private StockRestoration withItems(StockRestorationEntity entity) {
        return stockRestorationMapper.toDomain(entity, stockRestorationItemRepository.findAllByRestorationIdOrderBySkuIdAsc(entity.getId()));
    }
}
