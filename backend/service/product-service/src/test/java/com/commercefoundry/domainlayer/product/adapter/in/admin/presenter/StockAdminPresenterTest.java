package com.commercefoundry.domainlayer.product.adapter.in.admin.presenter;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.StockAdjustmentHistoryAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminListResponse;
import com.commercefoundry.domainlayer.product.application.info.StockAdjustmentHistoryInfo;
import com.commercefoundry.domainlayer.product.application.info.StockAdminInfo;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import com.commercefoundry.persistence.dto.PageResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class StockAdminPresenterTest {

    private static final Instant AT = Instant.parse("2026-10-18T09:00:00Z");

    private final StockAdminPresenter presenter = new StockAdminPresenter();

    @Test
    @DisplayName("재고 목록: 순서를 그대로 두고 SKU 아이디를 String 으로 바꾼다 — 수정 시각 null 은 null 이다")
    void stocks() {
        StockAdminListResponse response = presenter.toListResponse(List.of(
            new StockAdminInfo(4611686018427387905L, true, 0L, 0L, null), new StockAdminInfo(2L, false, 7L, 1L, AT)
        ));

        assertThat(response.stocks()).extracting(stock -> stock.skuId()).containsExactly("4611686018427387905", "2");
        assertThat(response.stocks().getFirst().updatedAt()).isNull();
        assertThat(response.stocks().get(1).reservedQuantity()).isEqualTo(1L);
    }

    @Test
    @DisplayName("조정 이력: 페이지 정보는 그대로, 아이디 · 어드민 아이디는 String, 사유는 code · name · description 메타데이터다")
    void adjustments() {
        StockAdjustmentHistoryInfo info = new StockAdjustmentHistoryInfo(
            9001L, 1L, 5L, 12L, 7L, false, true, StockAdjustmentReason.MANAGEMENT_CHANGE, null, 4611686018427387904L, AT
        );

        PageResponse<StockAdjustmentHistoryAdminItem> page = presenter.toAdjustmentPageResponse(new PageImpl<>(List.of(info), PageRequest.of(1, 1), 3L));

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.totalElements()).isEqualTo(3L);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.contents()).containsExactly(StockAdjustmentHistoryAdminItem.builder()
            .id("9001").beforeQuantity(5L).afterQuantity(12L).delta(7L).beforeStockManaged(false).afterStockManaged(true)
            .reason(new CodeNameDescriptionMetadata("MANAGEMENT_CHANGE", "관리 여부 변경", "재고 관리 여부(무제한 ↔ 수량 관리)를 바꿨습니다."))
            .memo(null).adminMemberId("4611686018427387904").createdAt(AT)
            .build());
    }
}
