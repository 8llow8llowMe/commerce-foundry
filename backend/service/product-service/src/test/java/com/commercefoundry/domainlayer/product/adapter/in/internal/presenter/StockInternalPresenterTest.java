package com.commercefoundry.domainlayer.product.adapter.in.internal.presenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.StockRestorationItemInternalItem;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockRestorationResponse;
import com.commercefoundry.domainlayer.product.application.info.StockRestorationInfo;
import com.commercefoundry.domainlayer.product.domain.model.StockRestorationReason;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockInternalPresenterTest {

    private final StockInternalPresenter presenter = new StockInternalPresenter();

    @Test
    @DisplayName("재고 복원: 식별자는 String, 사유는 enum 이름, 항목은 순서 · restored 를 그대로 옮긴다")
    void restoration() {
        StockRestorationInfo info = new StockRestorationInfo(4611686018427388101L, 4611686018427388201L, 4611686018427388001L, StockRestorationReason.RETURN, List.of(
            new StockRestorationInfo.Item(9001L, 1, true), new StockRestorationInfo.Item(9004L, 2, false)
        ));

        StockRestorationResponse response = presenter.toResponse(info);

        assertThat(response.restorationId()).isEqualTo("4611686018427388101");
        assertThat(response.claimId()).isEqualTo("4611686018427388201");
        assertThat(response.reservationId()).isEqualTo("4611686018427388001");
        assertThat(response.reason()).isEqualTo("RETURN");
        assertThat(response.items())
            .extracting(StockRestorationItemInternalItem::skuId, StockRestorationItemInternalItem::quantity, StockRestorationItemInternalItem::restored)
            .containsExactly(tuple("9001", 1, true), tuple("9004", 2, false));
    }
}
