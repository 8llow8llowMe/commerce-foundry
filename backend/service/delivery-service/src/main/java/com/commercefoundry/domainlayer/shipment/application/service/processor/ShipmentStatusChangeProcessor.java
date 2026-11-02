package com.commercefoundry.domainlayer.shipment.application.service.processor;

import com.commercefoundry.domainlayer.shipment.application.command.ShipmentStatusChangeCommand;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentErrorCode;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentException;
import com.commercefoundry.domainlayer.shipment.application.port.out.ShipmentRepositoryPort;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatusHistory;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 배송 상태 변경의 delivery 쪽 저장 단계 (#18 PR-B2, {@code order-flow-guide.md} §4 "배송 완료"). 배송 완료는 order 호출(외부 I/O)을 사이에 두고 단계마다
 * 새 트랜잭션({@code REQUIRES_NEW}, 5초)이다 — 송장 등록({@link ShipmentRegisterProcessor})과 같은 패턴이다.
 *
 * <ol>
 *   <li>{@link #begin} — 전이 판정 + 저장 + 이력. 배송 완료면 반영 대기({@code completion_pending=true}) · 배송 완료 시각 · 직전 상태({@code previous_status})를 함께 저장한다.</li>
 *   <li>order 200 → {@link #confirmCompletion} — 반영 대기를 내린다.</li>
 *   <li>order 404 · 409 {@code ORDER_020} · 400 시각 → {@link #revertCompletion} — 직전 상태로 되돌리고 되돌림 줄(자동 처리)을 더한다.</li>
 *   <li>order 409 {@code ORDER_025} · 결과 모름 → 아무것도 하지 않는다(반영 대기로 남아 같은 변경 재시도가 수렴한다).</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ShipmentStatusChangeProcessor {

    private final ShipmentRepositoryPort shipmentRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 판정 순서 — 없음 404 → 같은 상태(무변경, 반영 대기 배송 완료면 재전송) → 출고 미반영 409 {@code DELIVERY_025} → 허용 전이 밖 409 {@code DELIVERY_024} → 저장.
     *
     * <ul>
     *   <li>같은 상태 — 아무것도 바꾸지 않는다(이력도 더하지 않는다). 배송 완료가 반영 대기로 남아 있으면 처음 배송 완료 시각 그대로 order 에 다시 보내게 알린다
     *       (B1 등록 재시도와 같은 원칙 — order 가 처음 값을 이미 커밋했을 수 있다).</li>
     *   <li>배송 중 — delivery 안의 전이다. order 를 부르지 않는다.</li>
     *   <li>배송 완료 — 배송 완료 시각은 {@code now}(서버 시계, 마이크로초)이고 반영 대기로 저장한다.</li>
     * </ul>
     *
     * @throws ShipmentException {@code SHIPMENT_NOT_FOUND}(023) · {@code SHIPMENT_STATUS_TRANSITION_NOT_ALLOWED}(024) · {@code SHIPMENT_NOT_SYNCED}(025) ·
     *     {@code SHIPMENT_CONCURRENTLY_CHANGED}(026 — 읽은 뒤 다른 요청이 먼저 바꿨다)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public StatusChange begin(ShipmentStatusChangeCommand command, Instant now) {
        Shipment current = shipmentRepositoryPort.findById(command.shipmentId())
            .orElseThrow(() -> new ShipmentException(ShipmentErrorCode.SHIPMENT_NOT_FOUND));
        ShipmentStatus target = command.status();
        if (current.status() == target) {
            return new StatusChange(current, current.completionPending());
        }
        if (!current.orderSynced()) {
            throw new ShipmentException(ShipmentErrorCode.SHIPMENT_NOT_SYNCED);
        }
        if (!current.status().canTransitTo(target)) {
            throw new ShipmentException(ShipmentErrorCode.SHIPMENT_STATUS_TRANSITION_NOT_ALLOWED, current.status().getDisplayName(), target.getDisplayName());
        }
        Shipment changed = target == ShipmentStatus.IN_TRANSIT ? current.startTransit() : current.deliver(now);
        Shipment saved;
        try {
            saved = shipmentRepositoryPort.update(changed);
        } catch (OptimisticLockingFailureException exception) {
            throw new ShipmentException(ShipmentErrorCode.SHIPMENT_CONCURRENTLY_CHANGED, exception);
        }
        shipmentRepositoryPort.insertHistory(ShipmentStatusHistory.changedByAdmin(
            snowflakeIdGenerator.generateId(), saved.id(), current.status(), target, command.adminMemberId(), command.reason()));
        return new StatusChange(saved, saved.completionPending());
    }

    /**
     * order 가 배송 완료를 받아들였다 — 지금 행을 다시 읽어 반영 대기를 내린다. 다른 요청(같은 변경의 재시도)이 먼저 내렸으면 그대로 둔다.
     *
     * @return 지금 송장
     * @throws ShipmentException {@code SHIPMENT_CONCURRENTLY_CHANGED}(026) — 그 사이 송장이 없어졌거나 다른 배송 완료(다른 시각)로 바뀌었거나 버전이 겹쳤다.
     *     order 는 배송 완료다 — 송장은 반영 대기로 남고 같은 변경 재시도가 order 멱등 200 으로 수렴한다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public Shipment confirmCompletion(Shipment pending) {
        Shipment current = shipmentRepositoryPort.findById(pending.id())
            .orElseThrow(() -> new ShipmentException(ShipmentErrorCode.SHIPMENT_CONCURRENTLY_CHANGED));
        if (current.status() == ShipmentStatus.DELIVERED && !current.completionPending()) {
            return current;
        }
        if (!isSameDelivery(current, pending)) {
            throw new ShipmentException(ShipmentErrorCode.SHIPMENT_CONCURRENTLY_CHANGED);
        }
        try {
            return shipmentRepositoryPort.update(current.markCompletionSynced());
        } catch (OptimisticLockingFailureException exception) {
            throw new ShipmentException(ShipmentErrorCode.SHIPMENT_CONCURRENTLY_CHANGED, exception);
        }
    }

    /**
     * order 가 배송 완료를 거절했다(반영되지 않은 것이 확실하다) — tx1 이 행에 저장한 직전 상태({@code previous_status})로 되돌리고 되돌림 줄(자동 처리)을 더한다.
     * 지금 행이 이번에 보낸 배송 완료(같은 시각 · 반영 대기)가 아니면 건드리지 않는다 — 그 사이 다른 요청의 결과가 남는다. 저장은 버전 비교다.
     *
     * <p>직전 상태를 이력 줄에서 고르지 않는 이유 — 이력 순서는 Snowflake 아이디 · 생성 시각이라 여러 인스턴스의 시계가 어긋나면 마지막 배송 완료 줄을 잘못 고를 수 있고,
     * 이력이 없으면 되돌릴 수 없다. 반영 대기와 같은 행에 저장하면 같은 트랜잭션 · 같은 버전으로 움직인다(check {@code ck_shipment_previous_status_pending}).
     *
     * @return 되돌렸으면 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public boolean revertCompletion(Shipment pending) {
        Optional<Shipment> current = shipmentRepositoryPort.findById(pending.id());
        if (current.isEmpty() || !isSameDelivery(current.get(), pending)) {
            return false;
        }
        Shipment reverted;
        try {
            reverted = shipmentRepositoryPort.update(current.get().revertDelivery());
        } catch (OptimisticLockingFailureException exception) {
            return false;
        }
        shipmentRepositoryPort.insertHistory(ShipmentStatusHistory.completionReverted(snowflakeIdGenerator.generateId(), pending.id(), reverted.status()));
        return true;
    }

    private static boolean isSameDelivery(Shipment current, Shipment pending) {
        return current.completionPending() && Objects.equals(current.deliveredAt(), pending.deliveredAt());
    }

    /**
     * @param shipment 지금(바꾼 뒤 또는 그대로인) 송장
     * @param completionRequired true 면 배송 완료를 order 에 보내야 한다 — 이번에 배송 완료로 바꿨거나, 같은 상태 요청인데 반영 대기로 남아 있었다
     */
    public record StatusChange(Shipment shipment, boolean completionRequired) {

        public StatusChange {
            Objects.requireNonNull(shipment, "shipment");
        }
    }
}
