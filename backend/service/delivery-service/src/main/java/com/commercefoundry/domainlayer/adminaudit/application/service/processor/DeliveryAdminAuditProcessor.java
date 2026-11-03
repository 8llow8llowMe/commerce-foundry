package com.commercefoundry.domainlayer.adminaudit.application.service.processor;

import com.commercefoundry.domainlayer.adminaudit.application.model.DeliveryAdminAuditAction;
import com.commercefoundry.security.audit.AdminAuditLog;
import org.springframework.stereotype.Component;

/**
 * delivery-service 어드민 쓰기 감사 줄 ({@code admin.audit}, #163 — {@code security-guide.md} §11-4). DB 싱크가 없어 포트 · 어댑터 없이
 * security-core {@link AdminAuditLog} 를 부른다. 바꾼 값(택배사 · 송장 번호 · 상태)은 싣지 않는다 — 정본은 배송 상태 이력이다.
 */
@Component
public class DeliveryAdminAuditProcessor {

    /**
     * 송장 등록 한 건. 업무 결과가 정해진 뒤 Facade 에서 부른다 — 단계마다 {@code REQUIRES_NEW} 라 안쪽에서 부르면 안쪽 커밋 기준으로 남는다.
     *
     * <p>Facade 의 {@code NEVER} 범위에 Spring 이 빈 동기화를 켜도 실제 트랜잭션은 없다. {@link AdminAuditLog#write} 는 각 항목의 확정 결과를 즉시 남긴다.
     *
     * @param succeeded 등록 · 이미 등록이면 true, 건별 실패면 false
     */
    public void shipmentRegistered(long adminMemberId, long orderId, boolean succeeded) {
        AdminAuditLog.write(DeliveryAdminAuditAction.SHIPMENT_REGISTER.name(), adminMemberId, AdminAuditLog.target("order", orderId), succeeded);
    }

    /**
     * 배송 상태 변경 한 건 (#18 PR-B2). Facade({@code NEVER})가 {@code finally} 에서 판정한 결과를 즉시 남긴다. 응답 재조회가 실패해도 호출자는
     * {@code succeeded=false} 를 넘기므로 {@code FAILURE} 다 — 상태 변경 여부의 정본은 배송 상태 이력이다. 바꾼 상태 · 사유는 싣지 않는다.
     *
     * @param succeeded 바꿈 · 그대로 둠이면 true, 업무 오류면 false
     */
    public void shipmentStatusChanged(long adminMemberId, long shipmentId, boolean succeeded) {
        AdminAuditLog.write(DeliveryAdminAuditAction.SHIPMENT_STATUS_CHANGE.name(), adminMemberId, AdminAuditLog.target("delivery", shipmentId), succeeded);
    }
}
