package com.commercefoundry.domainlayer.product.application.command;

import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import java.util.Objects;
import lombok.Builder;

/**
 * 어드민 재고 조정. 수량은 절대값으로 바꾸고 기대 수량으로 충돌을 막는다 (CAS).
 *
 * @param quantity 바꿀 판매 가능 수량. null 이면 수량을 바꾸지 않는다
 * @param expectedQuantity 어드민이 화면에서 본 현재 수량. {@code quantity} 가 있을 때만 쓰고, 그때는 필수다
 * @param stockManaged 바꿀 재고 관리 여부. null 이면 바꾸지 않는다
 * @param memo 없으면 null
 * @param adminMemberId 조정한 어드민 (access 토큰의 memberId)
 */
@Builder
public record StockAdjustmentCommand(
    long skuId, Long quantity, Long expectedQuantity, Boolean stockManaged,
    StockAdjustmentReason reason, String memo, long adminMemberId
) {

    /** 요청 DTO 가 같은 규칙을 검증한다. 여기서는 다른 진입점이 생겨도 규칙이 깨지지 않게 한 번 더 막는다. */
    public StockAdjustmentCommand {
        Objects.requireNonNull(reason, "reason");
        if (quantity == null && stockManaged == null) {
            throw new IllegalArgumentException("quantity · stockManaged 중 하나는 있어야 합니다.");
        }
        if (quantity != null && expectedQuantity == null) {
            throw new IllegalArgumentException("quantity 를 바꾸려면 expectedQuantity 가 필요합니다.");
        }
        if ((quantity != null && quantity < 0) || (expectedQuantity != null && expectedQuantity < 0)) {
            throw new IllegalArgumentException("수량은 음수일 수 없습니다.");
        }
    }

    public boolean changesQuantity() {
        return quantity != null;
    }

    public boolean changesStockManaged() {
        return stockManaged != null;
    }
}
