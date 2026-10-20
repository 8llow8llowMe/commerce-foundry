package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.StockAdjustmentHistoryInfo;
import com.commercefoundry.domainlayer.product.application.info.StockAdminInfo;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockAdjustmentHistoryRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockReservationRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuReservedQuantityQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.StockQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.StockSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * 어드민 재고 조회 전용 — SKU 재고 목록과 조정 이력. 쓰지 않는다 (조정은 {@link StockAdjustmentProcessor}).
 * 트랜잭션은 호출한 Facade 가 읽기 전용으로 건다.
 *
 * <p>재고 목록의 쿼리는 SKU 존재 확인 → 재고 행 → 선점 수량 합 3번이고 모두 SKU 묶음 in 절이다 — SKU 수와 무관하다.
 *
 * <p><b>재고 행이 없는 SKU 는 품절(관리 true · 수량 0 · 선점 0, 수정 시각 null)로 낸다</b> ({@link StockSnapshot#empty()}).
 * 어드민 조정(PATCH)이 행 없는 SKU 의 기대 수량으로 0 을 요구하므로, 화면은 조회한 {@code availableQuantity} 를 그대로 {@code expectedQuantity} 로 보내면 된다.
 */
@Component
@RequiredArgsConstructor
public class StockAdminQueryProcessor {

    private final SkuRepositoryPort skuRepositoryPort;
    private final StockRepositoryPort stockRepositoryPort;
    private final StockReservationRepositoryPort stockReservationRepositoryPort;
    private final StockAdjustmentHistoryRepositoryPort stockAdjustmentHistoryRepositoryPort;

    /**
     * 없는 SKU 는 응답에서 뺀다 — 서비스 간 SKU 벌크 조회와 같은 관례다. 어드민 화면은 상품 상세에서 얻은 SKU 묶음을 한 번에 묻는데,
     * 그 사이 지워진 SKU 하나 때문에 묶음 전체를 404 로 막으면 나머지 SKU 도 조정할 수 없다. 호출한 쪽이 누락을 보고 판단한다.
     *
     * @param skuIds 요청 순서 그대로. 중복은 첫 등장 순서로 한 번만 센다
     * @return 요청 순서(중복 제거 후). 없는 SKU 는 빠진다
     */
    public List<StockAdminInfo> getStocks(List<Long> skuIds) {
        List<Long> distinctIds = skuIds.stream().distinct().toList();
        Set<Long> existingIds = skuRepositoryPort.findAllByIds(distinctIds).stream().map(Sku::id).collect(Collectors.toSet());
        if (existingIds.isEmpty()) {
            return List.of();
        }

        Map<Long, StockQueryResult> stocksBySkuId = stockRepositoryPort.findAllWithUpdatedAtBySkuIds(existingIds).stream()
            .collect(Collectors.toMap(StockQueryResult::skuId, Function.identity()));
        Map<Long, Long> reservedBySkuId = stockReservationRepositoryPort.sumReservedQuantitiesBySkuIds(existingIds).stream()
            .collect(Collectors.toMap(SkuReservedQuantityQueryResult::skuId, SkuReservedQuantityQueryResult::quantity));

        List<StockAdminInfo> infos = new ArrayList<>();
        for (Long skuId : distinctIds) {
            if (!existingIds.contains(skuId)) {
                continue;
            }
            long reservedQuantity = reservedBySkuId.getOrDefault(skuId, 0L);
            StockQueryResult stock = stocksBySkuId.get(skuId);
            infos.add(stock == null ? emptyStock(skuId, reservedQuantity)
                : new StockAdminInfo(skuId, stock.stockManaged(), stock.availableQuantity(), reservedQuantity, stock.updatedAt()));
        }
        return infos;
    }

    /**
     * SKU 하나의 조정 이력 — 최신순 한 페이지. 없는 SKU 는 {@code NOT_FOUND_SKU}(404)다 — 이력이 없는 SKU(빈 페이지)와 구분한다.
     * 쿼리는 SKU 존재 확인 → 이력 목록 → 전체 건수 3번이다.
     */
    public Page<StockAdjustmentHistoryInfo> getAdjustments(long skuId, int page, int size) {
        if (!skuRepositoryPort.existsById(skuId)) {
            throw new ProductException(ProductErrorCode.NOT_FOUND_SKU);
        }
        return stockAdjustmentHistoryRepositoryPort.findPageBySkuId(skuId, page, size).map(StockAdjustmentHistoryInfo::from);
    }

    /**
     * 재고 행이 없는 SKU — 품절 기본값. 행이 없으면 차감한 선점도 생길 수 없어(차감은 행이 있어야 1행이다) 선점 합은 사실상 0 이지만,
     * 읽은 값을 그대로 싣는다 — 0 으로 덮으면 데이터가 어긋났을 때 화면에서 보이지 않는다.
     */
    private static StockAdminInfo emptyStock(long skuId, long reservedQuantity) {
        StockSnapshot empty = StockSnapshot.empty();
        return new StockAdminInfo(skuId, empty.stockManaged(), empty.availableQuantity(), reservedQuantity, null);
    }
}
