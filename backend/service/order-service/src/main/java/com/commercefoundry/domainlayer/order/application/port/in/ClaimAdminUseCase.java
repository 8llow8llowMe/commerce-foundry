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

    /** 환불 실패를 대표 관리자가 PG 확인 뒤 환불 완료로 확정하고(PG 호출 없음) 재고 복원을 잇는다 (#17 A3). */
    ClaimAdminResponse confirmRefund(long claimId, long expectedVersion, String reason, long adminId);

    /** 재고 복원이 종결 실패한 취소를 복원 없이 종결한다 (#17 A3). */
    ClaimAdminResponse complete(long claimId, long expectedVersion, String reason, long adminId);
}
