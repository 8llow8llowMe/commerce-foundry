package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.persistence.dto.PageResponse;

/** 어드민 클레임 조회 · 결제 후 취소 수습 (#17). 수습 4개는 모두 처리 뒤의 상세를 돌려준다. */
public interface ClaimAdminUseCase {
    PageResponse<ClaimAdminItem> getClaims(ClaimAdminFilter filter, long adminId);

    ClaimAdminResponse getClaim(long claimId, long adminId);

    /** 종결 실패를 다시 열거나 대기 클레임을 선점해 환불 · 재고 복원을 지금 다시 돌린다. 이미 완료면 그대로다. */
    ClaimAdminResponse retry(long claimId, long adminId);

    /** 환불이 종결 거절된 취소를 거절하고 주문을 취소 요청 전 상태로 되돌린다. 이미 거절이면 그대로다. */
    ClaimAdminResponse reject(long claimId, String reason, long adminId);

    /** 운영자가 확인한 환불을 확정하고 주문을 취소 완료로 바꾼 뒤 재고 복원을 잇는다. 이미 환불 확정 뒤면 그대로다. */
    ClaimAdminResponse confirmRefund(long claimId, String reason, long adminId);

    /** 재고 복원이 종결 실패한 클레임을 복원 없이 완료로 닫는다. 이미 완료면 그대로다. */
    ClaimAdminResponse complete(long claimId, String reason, long adminId);
}
