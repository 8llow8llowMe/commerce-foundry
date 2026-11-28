package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.application.command.ClaimReturnApprovalCommand;
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

    /** 검수를 마친 반품 접수를 승인하고(환불액 서버 계산 · 화면 금액 대조) 환불 · 재고 복원을 잇는다 (#17 B2c-2). */
    ClaimAdminResponse approveReturn(ClaimReturnApprovalCommand command, long adminId);

    /** 반품 접수를 거절하고 같은 커밋에서 주문을 정리한다 — 환불 · 원격 호출 없음 (#17 B2c-2). */
    ClaimAdminResponse rejectReturn(long claimId, long expectedVersion, String reason, long adminId);

    /**
     * 결제사가 종결 거절한 결제 후 취소를, payment 가 무환불을 확정한 뒤에만 거절하고 주문을 취소 요청 전 상태로 되돌린다 — 재고 복원 · 환불 없음 (#17 A4).
     */
    ClaimAdminResponse rejectCancellation(long claimId, long expectedVersion, String reason, long adminId);
}
