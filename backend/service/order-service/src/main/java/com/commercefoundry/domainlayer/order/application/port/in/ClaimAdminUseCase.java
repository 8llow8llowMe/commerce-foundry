package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.persistence.dto.PageResponse;

public interface ClaimAdminUseCase {
    PageResponse<ClaimAdminItem> getClaims(ClaimAdminFilter filter, long adminId);
    ClaimAdminResponse getClaim(long claimId, long adminId);

    /** 결제 후 취소 클레임의 환불 · 재고 복원을 다시 시도하고 현재 상세를 돌려준다 (#17 A2b). */
    ClaimAdminResponse retry(long claimId, long expectedVersion, long adminId);
}
