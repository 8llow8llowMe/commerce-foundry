package com.commercefoundry.domainlayer.shipment.application.service.processor;

import com.commercefoundry.domainlayer.shipment.application.command.ShipmentRegisterCommand;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentErrorCode;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentException;
import com.commercefoundry.domainlayer.shipment.application.port.out.ShipmentRepositoryPort;
import com.commercefoundry.domainlayer.shipment.domain.model.OrderShipmentConfirmation;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
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
 * 송장 등록의 delivery 쪽 저장 단계 ({@code order-flow-guide.md} §4). order 호출(외부 I/O)을 사이에 두고 단계마다 새 트랜잭션({@code REQUIRES_NEW}, 5초)이다 —
 * 원격 호출 동안 커넥션 · 행 잠금을 잡지 않고, 한 건의 실패가 일괄 등록의 다른 건을 되돌리지 않는다.
 *
 * <ol>
 *   <li>{@link #prepare} — 대기 송장(주문 반영 전) INSERT + 등록 이력. 같은 주문의 송장이 이미 있으면 재시도 · 이미 등록 · 다른 송장으로 출고됨을 가른다.</li>
 *   <li>order 200 → {@link #confirm} — 반영 완료 표시와 회원 · 배송 범위 스냅숏. 버전 충돌이면 {@link #reconcile} 로 지금 행에 맞춘다.</li>
 *   <li>order 404 · 409 {@code ORDER_020} · 400 시각 범위 → {@link #discard} — 대기 송장 · 이력 삭제.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ShipmentRegisterProcessor {

    private final ShipmentRepositoryPort shipmentRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 송장이 없으면 대기 송장과 등록 이력을 만든다. 있으면 —
     * <ul>
     *   <li>주문 반영 전 — 택배사 · 송장 번호를 이번 요청 값으로 바꿔 다시 order 에 보낸다(같은 요청 재시도로 복구, 자동 재전송은 없다). <b>출고 시각은 처음 값</b>이다
     *       ({@link Shipment#retarget}). 이력은 더하지 않는다(상태가 그대로다) — 등록자는 처음 등록한 관리자이고, 재시도한 관리자는 감사 줄에 남는다.</li>
     *   <li>주문 반영 후 · 같은 택배사 · 송장 번호 — 이미 등록됨. 아무것도 바꾸지 않는다.</li>
     *   <li>주문 반영 후 · 다른 송장 — {@code DELIVERY_019}. 반영된 송장은 지우거나 바꾸지 않는다.</li>
     * </ul>
     *
     * @param now 등록 시각 — 새 송장이면 출고 시각이 된다(재시도면 쓰지 않는다)
     * @throws ShipmentException {@code SHIPMENT_ALREADY_SHIPPED}(019) · {@code SHIPMENT_CONCURRENTLY_REGISTERED}(020 — 같은 주문의 동시 등록)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public Registration prepare(ShipmentRegisterCommand.Item item, long adminMemberId, Instant now) {
        Optional<Shipment> existing = shipmentRepositoryPort.findByOrderId(item.orderId());
        if (existing.isPresent()) {
            return resume(existing.get(), item);
        }
        Shipment shipment = Shipment.register(snowflakeIdGenerator.generateId(), item.orderId(), item.carrier(), item.trackingNumber(), now);
        Shipment saved;
        try {
            saved = shipmentRepositoryPort.insert(shipment);
        } catch (OptimisticLockingFailureException exception) {
            // 읽은 뒤 같은 주문의 다른 등록이 먼저 INSERT 했다(유일 키). 어느 쪽 송장이 남을지 모르는 채 바꾸지 않는다 — 다시 보내면 남은 송장 기준으로 판정받는다
            throw new ShipmentException(ShipmentErrorCode.SHIPMENT_CONCURRENTLY_REGISTERED, exception);
        }
        shipmentRepositoryPort.insertHistory(ShipmentStatusHistory.registered(snowflakeIdGenerator.generateId(), saved.id(), adminMemberId));
        return new Registration(saved, false);
    }

    private Registration resume(Shipment existing, ShipmentRegisterCommand.Item item) {
        if (existing.orderSynced()) {
            if (existing.hasTracking(item.carrier(), item.trackingNumber())) {
                return new Registration(existing, true);
            }
            throw new ShipmentException(ShipmentErrorCode.SHIPMENT_ALREADY_SHIPPED);
        }
        try {
            return new Registration(shipmentRepositoryPort.update(existing.retarget(item.carrier(), item.trackingNumber())), false);
        } catch (OptimisticLockingFailureException exception) {
            throw new ShipmentException(ShipmentErrorCode.SHIPMENT_CONCURRENTLY_REGISTERED, exception);
        }
    }

    /**
     * order 가 출고를 받아들였다 — 반영 완료로 표시하고 회원 · 배송 범위를 스냅숏한다. 버전 비교라 {@link #prepare} 뒤 다른 요청이 같은 송장을 바꿨거나
     * 지웠으면 실패한다 — 호출 쪽이 {@link #reconcile} 로 지금 행에 맞춘다.
     *
     * @throws ShipmentException {@code SHIPMENT_CONCURRENTLY_REGISTERED}(020) — 낙관적 락 실패(행 없음 포함). 이 트랜잭션은 아무것도 바꾸지 않았다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public Shipment confirm(Shipment pending, OrderShipmentConfirmation confirmation) {
        requireSameOrder(pending, confirmation);
        try {
            return shipmentRepositoryPort.update(pending.markOrderSynced(confirmation.memberId(), confirmation.shippingScope()));
        } catch (OptimisticLockingFailureException exception) {
            throw new ShipmentException(ShipmentErrorCode.SHIPMENT_CONCURRENTLY_REGISTERED, exception);
        }
    }

    /**
     * {@link #confirm} 이 충돌한 뒤 지금 행에 맞춘다. order 200 은 "주문은 출고됐다" 의 증거다 — 송장을 반영 전으로 남겨 두지 않는다. 주문 아이디로 다시 읽어
     * <ul>
     *   <li>행 없음(그 사이 다른 요청이 지웠다) — 이번 요청의 송장(같은 아이디 · 택배사 · 송장 번호 · 출고 시각)을 반영 완료로 INSERT 하고 등록 이력을 남긴다.
     *       order 가 추적 로그에 남긴 {@code deliveryId} 와 같은 아이디다.</li>
     *   <li>반영 전 행 — 그 행을 반영 완료로 표시한다. 그 행의 택배사 · 송장 번호를 그대로 둔다(그 요청도 같은 주문을 order 에 보냈고 멱등 200 을 받는다).</li>
     *   <li>반영된 행 — 그대로 돌려준다.</li>
     * </ul>
     * 이번 요청의 송장과 같은 송장인지는 호출 쪽이 {@link Shipment#hasTracking} 으로 가른다.
     *
     * @param adminMemberId 행 없음으로 다시 만들 때 등록 이력에 남길 관리자
     * @return 반영 완료된 지금 송장
     * @throws ShipmentException {@code SHIPMENT_CONCURRENTLY_REGISTERED}(020) — 또 다른 요청과 겹쳤다(유일 키 · 버전). PostgreSQL 은 실패한 문장 뒤 같은
     *     트랜잭션에서 다시 읽지 못하므로, 호출 쪽이 새 트랜잭션으로 다시 부른다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public Shipment reconcile(Shipment pending, OrderShipmentConfirmation confirmation, long adminMemberId) {
        requireSameOrder(pending, confirmation);
        Optional<Shipment> current = shipmentRepositoryPort.findByOrderId(pending.orderId());
        try {
            if (current.isEmpty()) {
                Shipment restored = Shipment.register(pending.id(), pending.orderId(), pending.carrier(), pending.trackingNumber(), pending.shippedAt())
                    .markOrderSynced(confirmation.memberId(), confirmation.shippingScope());
                Shipment saved = shipmentRepositoryPort.insert(restored);
                shipmentRepositoryPort.insertHistory(ShipmentStatusHistory.registered(snowflakeIdGenerator.generateId(), saved.id(), adminMemberId));
                return saved;
            }
            if (!current.get().orderSynced()) {
                return shipmentRepositoryPort.update(current.get().markOrderSynced(confirmation.memberId(), confirmation.shippingScope()));
            }
            return current.get();
        } catch (OptimisticLockingFailureException exception) {
            throw new ShipmentException(ShipmentErrorCode.SHIPMENT_CONCURRENTLY_REGISTERED, exception);
        }
    }

    /**
     * order 가 출고를 거절했다(반영되지 않은 것이 확실하다) — 대기 송장 · 이력을 지운다. {@link #prepare} 뒤 다른 요청이 바꿨거나 반영했으면 지우지 않는다.
     *
     * @return 지웠으면 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public boolean discard(Shipment pending) {
        return shipmentRepositoryPort.deletePending(pending.id(), Objects.requireNonNull(pending.version(), "저장된 송장만 지운다"));
    }

    private static void requireSameOrder(Shipment pending, OrderShipmentConfirmation confirmation) {
        if (pending.orderId() != confirmation.orderId()) {
            throw new IllegalArgumentException("다른 주문의 반영 결과입니다: shipmentId=" + pending.id());
        }
    }

    /**
     * @param shipment 이번 요청이 order 에 보낼 송장(대기) 또는 이미 반영된 송장
     * @param alreadyRegistered true 면 같은 송장으로 이미 반영됐다 — order 를 부르지 않는다
     */
    public record Registration(Shipment shipment, boolean alreadyRegistered) {

        public Registration {
            Objects.requireNonNull(shipment, "shipment");
        }
    }
}
