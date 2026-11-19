package com.commercefoundry.domainlayer.picking.adapter.in.admin.presenter;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.picking.application.info.PickingBatchInfo;
import com.commercefoundry.domainlayer.picking.domain.model.PickingBatch;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSelection;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PickingBatchAdminPresenterTest {
    @Test
    void convertsLargeIdentifiersAndShowsFailuresAlongsideInspectionState() {
        var order = new PickingSnapshot(4611686018427387904L, "PREPARING", "KR", List.of(new PickingSnapshot.Line(10, 2, false)));
        var second = new PickingSnapshot(7, "PREPARING", "KR", List.of(new PickingSnapshot.Line(10, 3, false)));
        var batch = PickingBatch.create(4611686018427387905L, PickingSelection.judge(List.of(order.orderId(), 9L, second.orderId()), List.of(order, second)))
            .check(order.orderId(), Map.of(10L, 1L))
            .check(order.orderId(), Map.of(10L, 2L))
            .check(second.orderId(), Map.of(10L, 3L));
        var response = new PickingBatchAdminPresenter().present(new PickingBatchInfo(batch));
        assertThat(response.batchId()).isEqualTo("4611686018427387905");
        assertThat(response.orders().getLast().orderId()).isEqualTo("4611686018427387904");
        assertThat(response.exclusions().getFirst().orderId()).isEqualTo("9");
        assertThat(response.exclusions().getFirst().reason().code()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(response.pickingQuantities().getFirst().skuId()).isEqualTo("10");
        assertThat(response.pickingQuantities().getFirst().quantity()).isEqualTo(5);
        assertThat(response.totalQuantity()).isEqualTo(5);
        assertThat(response.inspectionComplete()).isTrue();
        assertThat(response.checks()).extracting(check -> check.reason().code()).containsExactly("SHORTAGE", "NONE", "NONE");
        assertThat(response.checks().getFirst().quantities().getFirst().quantity()).isEqualTo(1);
        assertThat(response.orders().getFirst().status()).isEqualTo("PREPARING");
    }
}
