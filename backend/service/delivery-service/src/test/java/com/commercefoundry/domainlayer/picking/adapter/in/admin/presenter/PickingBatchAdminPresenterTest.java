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
        var batch = PickingBatch.create(4611686018427387905L, PickingSelection.judge(List.of(order.orderId()), List.of(order)))
            .check(order.orderId(), Map.of(10L, 1L))
            .check(order.orderId(), Map.of(10L, 2L));
        var response = new PickingBatchAdminPresenter().present(new PickingBatchInfo(batch));
        assertThat(response.batchId()).isEqualTo("4611686018427387905");
        assertThat(response.orders().getFirst().orderId()).isEqualTo("4611686018427387904");
        assertThat(response.inspectionComplete()).isTrue();
        assertThat(response.checks()).extracting(check -> check.reason().code()).containsExactly("SHORTAGE", "NONE");
        assertThat(response.orders().getFirst().status()).isEqualTo("PREPARING");
    }
}
