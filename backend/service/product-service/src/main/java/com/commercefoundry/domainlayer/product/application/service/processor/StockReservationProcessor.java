package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.StockReservationCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.StockReservationInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockReservationRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuStocks;
import com.commercefoundry.domainlayer.product.domain.model.StockReleaseReason;
import com.commercefoundry.domainlayer.product.domain.model.StockReservation;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationItem;
import com.commercefoundry.global.properties.StockProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 재고 선점 · 확정 · 해제 · 만료 ({@code order-flow-guide.md} §3). 트랜잭션은 호출한 Facade 가 건다 — 선점 행과 재고 차감 · 복원이
 * 함께 커밋되거나 함께 롤백된다.
 *
 * <p>재고 쓰기는 조건부 UPDATE 한 문장이다 ({@code StockRepositoryPort}). 여러 SKU 는 <b>SKU 아이디 오름차순</b>으로 차감 · 복원한다 —
 * 조건부 UPDATE 가 행 잠금을 잡는 순서라, 모든 선점 · 해제가 같은 순서로 잠가 교착을 막는다. SKU 마다 한 문장이 필요해 루프 안에서 부른다
 * (in 절로 묶으면 SKU 마다 다른 수량 조건을 걸 수 없고, 어느 SKU 가 부족한지 알 수 없다).
 *
 * <p>선점 행 상태는 도메인 전이({@link StockReservation#confirm} · {@link StockReservation#release}) 뒤 저장한다 — 헤더의 {@code @Version} 이
 * 동시 확정 · 해제 · 만료 중 하나만 통과시키고, 진 쪽은 {@code STOCK_RESERVATION_CONFLICT}(409)로 롤백된다. 상태를 먼저 저장(flush)한 뒤
 * 재고를 되돌리므로 진 쪽은 복원까지 가지 않는다 — 같은 해제가 동시에 와도 재고는 한 번만 늘어난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReservationProcessor {

    private final StockReservationRepositoryPort stockReservationRepositoryPort;
    private final StockRepositoryPort stockRepositoryPort;
    private final SkuRepositoryPort skuRepositoryPort;
    private final ProductRepositoryPort productRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final StockProperties stockProperties;
    private final Clock clock;

    /**
     * 주문 하나의 재고를 선점한다. 같은 주문의 활성 선점이 있으면 항목이 같을 때 그대로 돌려주고(멱등), 다르면 409 다.
     *
     * <p>순서 — 판매 가능 확인(SKU · 상품 in 절) → 재고 행 in 절 → 선점 행 INSERT(같은 주문의 동시 생성은 유니크 키로 409) → SKU 오름차순 차감.
     * 차감은 하나가 실패해도 끝까지 돌려 부족한 SKU 를 모두 모은 뒤 {@code STOCK_INSUFFICIENT} 로 실패한다 — 예외가 트랜잭션을 롤백해
     * 앞서 차감한 재고와 선점 행이 함께 사라진다 (전체 해제).
     */
    public StockReservationInfo reserve(StockReservationCommand command) {
        Optional<StockReservation> active = stockReservationRepositoryPort.findActiveByOrderId(command.orderId());
        if (active.isPresent()) {
            return sameOrder(active.get(), command);
        }

        List<Long> skuIds = command.skuIds();
        validatePurchasable(skuIds);
        // 재고 행이 없는 SKU 는 품절이다 — snapshotOf 가 관리 true · 수량 0 을 내므로 차감 대상이 되고, 차감이 0행이라 부족으로 모인다
        SkuStocks stocks = SkuStocks.of(stockRepositoryPort.findAllBySkuIds(skuIds));

        Instant now = clock.instant();
        List<StockReservationItem> items = command.items().stream()
            .map(item -> new StockReservationItem(
                snowflakeIdGenerator.generateId(), item.skuId(), item.quantity(), stocks.snapshotOf(item.skuId()).stockManaged()
            ))
            .toList();
        StockReservation reservation = StockReservation.reserve(
            snowflakeIdGenerator.generateId(), command.orderId(), items, now.plus(stockProperties.reservation().ttl())
        );
        stockReservationRepositoryPort.create(reservation);

        List<Long> insufficientSkuIds = new ArrayList<>();
        for (StockReservationItem item : reservation.deductedItems()) {
            if (!stockRepositoryPort.deduct(item.skuId(), item.quantity())) {
                insufficientSkuIds.add(item.skuId());
            }
        }
        if (!insufficientSkuIds.isEmpty()) {
            throw new ProductException(ProductErrorCode.STOCK_INSUFFICIENT, insufficientSkuIds);
        }
        return StockReservationInfo.from(reservation);
    }

    /** 결제 완료 확정. 이미 확정이면 그대로 200, 해제 · 만료됐으면 409 {@code STOCK_RESERVATION_RELEASED}. */
    public StockReservationInfo confirm(long reservationId) {
        StockReservation.Transition transition = getReservation(reservationId).confirm(clock.instant());
        if (transition.changed()) {
            stockReservationRepositoryPort.update(transition.reservation());
        }
        return StockReservationInfo.from(transition.reservation());
    }

    /** 호출한 쪽의 해제 (결제 전 취소 · 주문 생성 실패). 이미 해제면 그대로 200, 확정됐으면 409 {@code STOCK_RESERVATION_CONFIRMED}. */
    public StockReservationInfo release(long reservationId) {
        StockReservation.Transition transition = getReservation(reservationId).release(clock.instant(), StockReleaseReason.RELEASED_BY_CALLER);
        if (transition.changed()) {
            saveAndRestore(transition.reservation());
        }
        return StockReservationInfo.from(transition.reservation());
    }

    /** 만료 대상 아이디 — 만료 시각 오름차순으로 {@code limit} 건까지. */
    public List<Long> findExpiredReservationIds(int limit) {
        return stockReservationRepositoryPort.findExpiredReservationIds(clock.instant(), limit);
    }

    /**
     * 만료된 선점 한 건을 푼다. 목록을 읽은 뒤 확정 · 해제됐을 수 있으므로 다시 읽어 RESERVED 이고 만료 시각이 지났을 때만 푼다.
     *
     * @return 풀었으면 true. 없거나 이미 확정 · 해제됐거나 아직 만료 전이면 false
     */
    public boolean expire(long reservationId) {
        Instant now = clock.instant();
        Optional<StockReservation> found = stockReservationRepositoryPort.findById(reservationId);
        if (found.isEmpty() || !found.get().isExpired(now)) {
            return false;
        }
        saveAndRestore(found.get().release(now, StockReleaseReason.EXPIRED).reservation());
        return true;
    }

    private StockReservationInfo sameOrder(StockReservation active, StockReservationCommand command) {
        if (!active.hasSameQuantities(command.quantitiesBySkuId())) {
            throw new ProductException(ProductErrorCode.STOCK_RESERVATION_ITEMS_MISMATCH);
        }
        return StockReservationInfo.from(active);
    }

    /**
     * 판매 가능 확인 — 서비스 간 SKU 조회의 {@code purchasable} 과 같은 규칙({@link SaleAvailability#isOpenForSale})에서 재고만 뺀다.
     * 수량은 조건부 차감이 판정한다. 없는 SKU · 없거나 숨긴 상품 · 판매 종료를 모두 모아 한 번에 알린다.
     */
    private void validatePurchasable(List<Long> skuIds) {
        List<Sku> skus = skuRepositoryPort.findAllByIds(skuIds);
        Map<Long, Sku> skusById = skus.stream().collect(Collectors.toMap(Sku::id, Function.identity()));
        List<Long> productIds = skus.stream().map(Sku::productId).distinct().toList();
        Map<Long, Product> productsById = productRepositoryPort.findAllByIdsIncludingHidden(productIds).stream()
            .collect(Collectors.toMap(Product::id, Function.identity()));

        List<Long> notPurchasable = skuIds.stream()
            .filter(skuId -> {
                Sku sku = skusById.get(skuId);
                Product product = sku == null ? null : productsById.get(sku.productId());
                return product == null || !SaleAvailability.isOpenForSale(product, sku);
            })
            .toList();
        if (!notPurchasable.isEmpty()) {
            throw new ProductException(ProductErrorCode.STOCK_SKU_NOT_PURCHASABLE, notPurchasable);
        }
    }

    /** 상태를 먼저 저장(버전 검사)하고, 이긴 쪽만 차감했던 재고를 SKU 오름차순으로 되돌린다. */
    private void saveAndRestore(StockReservation released) {
        stockReservationRepositoryPort.update(released);
        for (StockReservationItem item : released.deductedItems()) {
            if (!stockRepositoryPort.restore(item.skuId(), item.quantity())) {
                // 재고 행은 지우지 않는다 — 차감했던 행이 없으면 데이터 오류다. 해제는 막지 않고 남긴다
                log.warn("stock restore skipped, stock row missing. reservationId={}, skuId={}, quantity={}", released.id(), item.skuId(), item.quantity());
            }
        }
    }

    private StockReservation getReservation(long reservationId) {
        return stockReservationRepositoryPort.findById(reservationId)
            .orElseThrow(() -> new ProductException(ProductErrorCode.NOT_FOUND_STOCK_RESERVATION));
    }
}
