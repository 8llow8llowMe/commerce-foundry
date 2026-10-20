package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.StockRestorationCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.StockRestorationInfo;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockReservationRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRestorationRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuRestoredQuantityQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.StockReservation;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationItem;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import com.commercefoundry.domainlayer.product.domain.model.StockRestoration;
import com.commercefoundry.domainlayer.product.domain.model.StockRestorationItem;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 결제 후 취소 · 반품 입고의 재고 복원 ({@code order-flow-guide.md} §3). 트랜잭션은 호출한 Facade 가 건다 — 복원 기록과 재고 증가가
 * 함께 커밋되거나 함께 롤백된다.
 *
 * <p>확정된 선점의 상태는 바꾸지 않는다(CONFIRMED 그대로). 복원은 클레임마다 append-only 기록({@link StockRestoration})으로 남기고,
 * 그 기록으로 멱등(클레임 아이디)과 상한(선점 수량 − 이미 복원한 수량)을 판정한다.
 *
 * <p><b>선점 헤더를 비관적 잠금으로 먼저 읽는다</b> — 복원은 상태 전이가 없어 {@code @Version} 이 경합을 잡지 못한다. 잠금이 없으면 같은 선점의
 * 서로 다른 클레임 두 건이 "이미 복원한 수량" 을 각자 읽고 둘 다 통과해 확정 수량보다 많이 되돌린다. 같은 클레임의 동시 재요청도 이 잠금에서 줄을 서
 * 늦은 쪽이 먼저 커밋된 복원을 보고(READ COMMITTED — 문장마다 최신 커밋) 멱등 200 이 된다. 잠금 순서는 선점 헤더 → 재고 행(SKU 오름차순)으로
 * 해제 · 만료와 같아 교착이 없다.
 *
 * <p>어댑터가 던진 409({@code STOCK_RESTORATION_CONFLICT})는 flush 가 실패한 뒤라 트랜잭션이 rollback-only 다 — 재시도는 호출한 쪽이 새 트랜잭션으로 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockRestorationProcessor {

    private final StockReservationRepositoryPort stockReservationRepositoryPort;
    private final StockRestorationRepositoryPort stockRestorationRepositoryPort;
    private final StockRepositoryPort stockRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 클레임 하나의 재고를 복원한다. 같은 클레임의 복원이 있으면 내용이 같을 때 그대로 돌려주고(멱등), 다르면 409 다.
     *
     * <p>순서 — 선점 헤더 잠금 읽기 → 클레임의 기존 복원 → CONFIRMED 확인 → 이미 복원한 수량(group by 1번) → 상한 검사 → 복원 기록 INSERT →
     * 차감했던 SKU 만 오름차순 조건부 복원.
     * <ul>
     *   <li>기존 복원 확인을 상태 확인보다 먼저 한다 — 재요청은 선점 상태와 무관하게 처음 결과로 답한다.</li>
     *   <li>상한을 넘는 SKU 를 모두 모아 {@code STOCK_RESTORATION_QUANTITY_EXCEEDED} 로 실패한다. 아무것도 쓰기 전이다.</li>
     *   <li>기록을 재고 복원보다 먼저 쓴다 — 같은 클레임의 동시 생성이 잠금을 비켜 와도(다른 선점 아이디로 온 경우) 클레임 유니크 키에서 409 로 끝나
     *       재고를 건드리지 않는다.</li>
     * </ul>
     */
    public StockRestorationInfo restore(StockRestorationCommand command) {
        StockReservation reservation = stockReservationRepositoryPort.findByIdForUpdate(command.reservationId())
            .orElseThrow(() -> new ProductException(ProductErrorCode.NOT_FOUND_STOCK_RESERVATION));

        Optional<StockRestoration> existing = stockRestorationRepositoryPort.findByClaimId(command.claimId());
        if (existing.isPresent()) {
            return sameClaim(existing.get(), command);
        }
        if (reservation.status() != StockReservationStatus.CONFIRMED) {
            throw new ProductException(ProductErrorCode.STOCK_RESTORATION_RESERVATION_NOT_CONFIRMED);
        }

        Map<Long, Long> restoredBySkuId = stockRestorationRepositoryPort.sumRestoredQuantitiesByReservationId(reservation.id()).stream()
            .collect(Collectors.toMap(SkuRestoredQuantityQueryResult::skuId, SkuRestoredQuantityQueryResult::quantity));
        List<Long> exceededSkuIds = reservation.skuIdsExceedingRestorable(command.quantitiesBySkuId(), restoredBySkuId);
        if (!exceededSkuIds.isEmpty()) {
            throw new ProductException(ProductErrorCode.STOCK_RESTORATION_QUANTITY_EXCEEDED, exceededSkuIds);
        }

        Map<Long, Boolean> deductedBySkuId = reservation.items().stream()
            .collect(Collectors.toMap(StockReservationItem::skuId, StockReservationItem::deducted));
        List<StockRestorationItem> items = command.items().stream()
            .map(item -> new StockRestorationItem(snowflakeIdGenerator.generateId(), item.skuId(), item.quantity(), deductedBySkuId.get(item.skuId())))
            .toList();
        StockRestoration restoration = new StockRestoration(
            snowflakeIdGenerator.generateId(), command.claimId(), reservation.id(), reservation.orderId(), command.reason(), items
        );
        stockRestorationRepositoryPort.create(restoration);

        for (StockRestorationItem item : restoration.restoredItems()) {
            if (!stockRepositoryPort.restore(item.skuId(), item.quantity())) {
                // 해제 · 만료와 같다 — 재고 행은 지우지 않으므로 차감했던 행이 없으면 데이터 오류다. 복원 기록은 남기고 막지 않는다
                log.warn("stock restore skipped, stock row missing. restorationId={}, reservationId={}, skuId={}, quantity={}",
                    restoration.id(), reservation.id(), item.skuId(), item.quantity());
            }
        }
        return StockRestorationInfo.from(restoration);
    }

    private static StockRestorationInfo sameClaim(StockRestoration existing, StockRestorationCommand command) {
        if (!existing.isSameRequest(command.reservationId(), command.reason(), command.quantitiesBySkuId())) {
            throw new ProductException(ProductErrorCode.STOCK_RESTORATION_MISMATCH);
        }
        return StockRestorationInfo.from(existing);
    }
}
