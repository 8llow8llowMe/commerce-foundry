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
     * <p>Facade 는 {@code NEVER} 지만 Spring 은 트랜잭션 없는 범위에도 동기화를 켠다({@code SYNCHRONIZATION_ALWAYS}) — 그래서 {@link AdminAuditLog#write} 는 줄을
     * 바로 쓰지 않고 동기화에 걸어, <b>Facade 가 끝날 때(일괄 요청의 모든 건을 처리한 뒤) 한꺼번에</b> 남긴다. 빈 범위라 결과는 언제나 커밋이고 {@code succeeded}
     * 그대로다. 줄마다 바로 쓰는 공통 개선은 #163 후속이다.
     *
     * @param succeeded 등록 · 이미 등록이면 true, 건별 실패면 false
     */
    public void shipmentRegistered(long adminMemberId, long orderId, boolean succeeded) {
        AdminAuditLog.write(DeliveryAdminAuditAction.SHIPMENT_REGISTER.name(), adminMemberId, AdminAuditLog.target("order", orderId), succeeded);
    }

    /**
     * 배송 상태 변경 한 건 (#18 PR-B2). 등록과 같이 Facade({@code NEVER})가 결과가 정해진 뒤 부르고, 요청이 끝날 때 남는다 — 예외로 끝나면 빈 범위가 롤백으로 끝나
     * {@code succeeded} 와 상관없이 {@code FAILURE} 다. 바꾼 상태 · 사유는 싣지 않는다(정본은 배송 상태 이력).
     *
     * @param succeeded 바꿈 · 그대로 둠이면 true, 업무 오류면 false
     */
    public void shipmentStatusChanged(long adminMemberId, long shipmentId, boolean succeeded) {
        AdminAuditLog.write(DeliveryAdminAuditAction.SHIPMENT_STATUS_CHANGE.name(), adminMemberId, AdminAuditLog.target("delivery", shipmentId), succeeded);
    }
}
