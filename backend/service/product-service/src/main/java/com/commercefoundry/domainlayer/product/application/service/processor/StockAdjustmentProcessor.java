package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.StockAdjustmentCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.StockAdminInfo;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockAdjustmentHistoryRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockReservationRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.StockQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentHistory;
import com.commercefoundry.domainlayer.product.domain.model.StockSnapshot;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 어드민 재고 조정 — 수량은 절대값 + 기대 수량 CAS, 관리 여부는 그대로 덮어쓴다. 요청 한 번에 이력 한 건을 남긴다.
 * 트랜잭션은 호출한 Facade 가 건다 — 재고 변경과 이력이 함께 커밋되거나 함께 롤백된다.
 *
 * <p>쓰기는 모두 조건부 UPDATE 한 문장이다 ({@code coding-conventions.md} §9-5). 읽은 값으로 수량을 계산해 쓰지 않는다 —
 * 어드민이 화면을 보는 사이 주문이 차감하면 그 차감을 덮어쓰게 된다. 기대 수량이 다르면 {@code STOCK_ADJUSTMENT_CONFLICT}(409)로 알리고,
 * 어드민이 현재 수량을 다시 보고 조정한다.
 *
 * <p>재고 행이 없는 SKU 는 품절(재고 관리 · 수량 0)로 본다 ({@link StockSnapshot#empty()}). 그래서 첫 등록의 조정 전 값은 관리 true · 수량 0 이고
 * 기대 수량도 0 이어야 한다. 관리 여부를 보내지 않은 첫 등록은 재고 관리(true)로 만든다.
 *
 * <p>판매 가능 수량은 결제 전 선점분이 빠진 값이다 — 어드민이 실사 수량을 그대로 넣으면 선점분만큼 초과 판매된다. 그래서 응답에 선점 수량
 * ({@code reservedQuantity})을 함께 실어, 실사 입력은 {@code 실물 − reservedQuantity} 로 하게 한다 ({@code order-flow-guide.md} §3).
 */
@Component
@RequiredArgsConstructor
public class StockAdjustmentProcessor {

    private final SkuRepositoryPort skuRepositoryPort;
    private final StockRepositoryPort stockRepositoryPort;
    private final StockAdjustmentHistoryRepositoryPort stockAdjustmentHistoryRepositoryPort;
    private final StockReservationRepositoryPort stockReservationRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public StockAdminInfo adjust(StockAdjustmentCommand command) {
        long skuId = command.skuId();
        if (!skuRepositoryPort.existsById(skuId)) {
            throw new ProductException(ProductErrorCode.NOT_FOUND_SKU);
        }

        Optional<Stock> current = stockRepositoryPort.findBySkuId(skuId).map(StockQueryResult::toStock);
        if (current.isPresent()) {
            changeExisting(command);
        } else {
            createFirst(command);
        }

        // 조건부 UPDATE · INSERT 가 행 잠금을 쥔 채라 커밋 전까지 다른 트랜잭션이 이 행을 바꾸지 못한다 — 다시 읽은 값이 이 조정의 결과다
        StockQueryResult after = stockRepositoryPort.findBySkuId(skuId)
            .orElseThrow(() -> new IllegalStateException("조정한 재고 행을 다시 읽지 못했습니다. skuId=" + skuId));
        Stock before = current.map(stock -> beforeOf(command, stock, after)).orElseGet(() -> emptyStock(skuId));
        stockAdjustmentHistoryRepositoryPort.append(StockAdjustmentHistory.of(
            snowflakeIdGenerator.generateId(), before, after.toStock(), command.reason(), command.memo(), command.adminMemberId()
        ));
        // 선점 수량은 행 잠금 밖의 값이다 — 응답 순간의 참고치이고, 조정 판정(CAS)에는 쓰지 않는다
        long reservedQuantity = stockReservationRepositoryPort.sumReservedQuantityBySkuId(skuId);
        return new StockAdminInfo(after.skuId(), after.stockManaged(), after.availableQuantity(), reservedQuantity, after.updatedAt());
    }

    /** 재고 행이 없는 SKU 의 첫 등록. 동시에 다른 어드민이 먼저 등록했으면 어댑터가 PK 충돌을 409 로 바꾼다 — 덮어쓰지 않는다. */
    private void createFirst(StockAdjustmentCommand command) {
        StockSnapshot empty = StockSnapshot.empty();
        if (command.changesQuantity() && command.expectedQuantity() != empty.availableQuantity()) {
            throw new ProductException(ProductErrorCode.STOCK_ADJUSTMENT_CONFLICT);
        }
        boolean stockManaged = command.changesStockManaged() ? command.stockManaged() : empty.stockManaged();
        long quantity = command.changesQuantity() ? command.quantity() : empty.availableQuantity();
        stockRepositoryPort.create(new Stock(command.skuId(), stockManaged, quantity));
    }

    /** 있는 재고 행의 조정. 수량은 기대 수량 CAS 가 성공해야 바뀐다. */
    private void changeExisting(StockAdjustmentCommand command) {
        long skuId = command.skuId();
        if (command.changesQuantity() && !stockRepositoryPort.updateQuantityIfMatches(skuId, command.expectedQuantity(), command.quantity())) {
            throw new ProductException(ProductErrorCode.STOCK_ADJUSTMENT_CONFLICT);
        }
        if (command.changesStockManaged() && !stockRepositoryPort.updateStockManaged(skuId, command.stockManaged())) {
            // 재고 행은 지우지 않으므로 방금 읽은 행이 사라질 일은 없다 — 생기면 동시 변경으로 보고 다시 시도하게 한다
            throw new ProductException(ProductErrorCode.STOCK_ADJUSTMENT_CONFLICT);
        }
    }

    /**
     * 있는 재고 행의 조정 전 값.
     *
     * <ul>
     *   <li>수량 — CAS 가 성공했다면 그 순간의 수량이 기대 수량이었다. 관리 여부만 바꾼 조정은 수량과 무관하므로 조정 후 수량과 같게 둔다(delta 0).
     *       처음 읽은 수량을 쓰면 읽은 뒤 커밋된 주문 차감이 어드민 조정처럼 기록된다.</li>
     *   <li>관리 여부 — 처음 읽은 값이다. 두 어드민이 동시에 관리 여부를 바꾸면 늦은 쪽 이력의 조정 전 값이 한 단계 늦을 수 있다. 결과 값은 정확하다.</li>
     * </ul>
     */
    private static Stock beforeOf(StockAdjustmentCommand command, Stock current, StockQueryResult after) {
        long beforeQuantity = command.changesQuantity() ? command.expectedQuantity() : after.availableQuantity();
        return new Stock(current.skuId(), current.stockManaged(), beforeQuantity);
    }

    /** 재고 행이 없던 SKU 의 조정 전 값 — 품절(관리 true · 수량 0)이다. */
    private static Stock emptyStock(long skuId) {
        StockSnapshot empty = StockSnapshot.empty();
        return new Stock(skuId, empty.stockManaged(), empty.availableQuantity());
    }
}
