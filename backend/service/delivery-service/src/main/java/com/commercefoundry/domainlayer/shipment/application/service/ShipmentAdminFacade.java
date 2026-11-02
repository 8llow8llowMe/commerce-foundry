package com.commercefoundry.domainlayer.shipment.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.DeliveryAdminAuditProcessor;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentRegisterAdminResponse;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.presenter.ShipmentAdminPresenter;
import com.commercefoundry.domainlayer.shipment.application.command.ShipmentRegisterCommand;
import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentErrorCode;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentException;
import com.commercefoundry.domainlayer.shipment.application.info.ShipmentRegisterResultInfo;
import com.commercefoundry.domainlayer.shipment.application.port.in.ShipmentAdminUseCase;
import com.commercefoundry.domainlayer.shipment.application.service.processor.OrderShipmentProcessor;
import com.commercefoundry.domainlayer.shipment.application.service.processor.ShipmentRegisterProcessor;
import com.commercefoundry.domainlayer.shipment.application.service.processor.ShipmentRegisterProcessor.Registration;
import com.commercefoundry.domainlayer.shipment.domain.model.OrderShipmentConfirmation;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 송장 등록 (#18 PR-B1, {@code order-flow-guide.md} §4). order-service Feign 호출이 섞여 이 Facade 는 트랜잭션 밖이다({@code NEVER}) —
 * 저장 단계마다 {@link ShipmentRegisterProcessor} 가 새 트랜잭션을 건다. 일괄 등록은 주문마다 따로 처리해 한 건의 실패가 다른 건을 막거나 되돌리지 않는다.
 *
 * <p>주문마다 order 를 한 번씩 부른다 — 출고 반영 internal 은 주문 단위 명령(주문마다 {@code REQUIRES_NEW} · 건별 결과)이라 벌크 엔드포인트를 두지 않았다
 * ({@code coding-conventions.md} §9-6 의 예외, 상한 20건). order 장애면 서킷이 열려 남은 건은 바로 결과 모름으로 끝난다.
 * 요청 전체는 {@link #REQUEST_DEADLINE} 안에 새 건을 시작한다 — 넘기면 남은 건은 시작하지 않고 {@code DELIVERY_022} 다.
 *
 * <p>감사 줄은 주문마다 결과가 정해진 뒤 남긴다 — 등록 · 이미 등록은 {@code SUCCESS}, 건별 실패는 {@code FAILURE}. 송장 번호는 로그 · 감사 줄에 싣지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class ShipmentAdminFacade implements ShipmentAdminUseCase {

    /**
     * 한 요청에서 새 건을 시작할 수 있는 마감(요청 시작 기준, 서버 {@link Clock}). 게이트웨이 {@code response-timeout} 이 10초라 그 뒤의 응답은 운영자에게
     * 닿지 않는다(504) — 처리한 건의 결과를 잃지 않게 2초를 남기고 끊는다. 마감은 건을 시작하기 전에만 본다: 진행 중인 건은 끝까지 간다(정상이면 수십~수백 ms,
     * order 가 read timeout 3초까지 끌면 10초를 넘길 수 있다 — 그 건은 행이 남아 같은 요청 재시도로 수렴한다).
     */
    static final Duration REQUEST_DEADLINE = Duration.ofSeconds(8);

    /** 반영 완료 저장이 충돌한 뒤 지금 행에 맞추는 시도 횟수 — 유일 키 경합으로 실패한 트랜잭션은 다시 읽지 못해 새 트랜잭션으로 한 번 더 본다. */
    private static final int RECONCILE_ATTEMPTS = 2;

    private final ShipmentRegisterProcessor shipmentRegisterProcessor;
    private final OrderShipmentProcessor orderShipmentProcessor;
    private final DeliveryAdminAuditProcessor deliveryAdminAuditProcessor;
    private final ShipmentAdminPresenter shipmentAdminPresenter;
    private final Clock clock;

    @Override
    public ShipmentRegisterAdminResponse register(ShipmentRegisterCommand command) {
        Instant deadline = clock.instant().plus(REQUEST_DEADLINE);
        List<ShipmentRegisterResultInfo> results = new ArrayList<>(command.items().size());
        int skipped = 0;
        for (ShipmentRegisterCommand.Item item : command.items()) {
            ShipmentRegisterResultInfo result;
            if (clock.instant().isBefore(deadline)) {
                result = registerOne(item, command.adminMemberId());
            } else {
                // 송장 행을 만들지 않고 order 도 부르지 않는다 — 다시 보내면 처음 등록이다
                result = failed(item.orderId(), null, new ShipmentException(ShipmentErrorCode.SHIPMENT_REGISTER_DEADLINE_EXCEEDED));
                skipped++;
            }
            deliveryAdminAuditProcessor.shipmentRegistered(command.adminMemberId(), item.orderId(), result.succeeded());
            results.add(result);
        }
        if (skipped > 0) {
            log.warn("shipment register deadline exceeded. requested={}, skipped={}", command.items().size(), skipped);
        }
        return shipmentAdminPresenter.toRegisterResponse(results);
    }

    /** 한 건 — 저장 → order 반영 → 반영 완료 표시 또는 대기 송장 삭제. 실패는 예외가 아니라 결과로 바꾼다. */
    private ShipmentRegisterResultInfo registerOne(ShipmentRegisterCommand.Item item, long adminMemberId) {
        long orderId = item.orderId();
        Registration registration;
        try {
            registration = shipmentRegisterProcessor.prepare(item, adminMemberId, clock.instant());
        } catch (ShipmentException exception) {
            return failed(orderId, null, exception);
        } catch (RuntimeException exception) {
            log.error("shipment prepare failed. orderId={}, error={}", orderId, exception.getClass().getSimpleName());
            return failed(orderId, null, new ShipmentException(ShipmentErrorCode.SHIPMENT_REGISTER_FAILED));
        }
        Shipment pending = registration.shipment();
        if (registration.alreadyRegistered()) {
            return ShipmentRegisterResultInfo.alreadyRegistered(orderId, pending.id());
        }

        OrderShipmentConfirmation confirmation;
        try {
            confirmation = orderShipmentProcessor.ship(pending);
        } catch (OrderShipmentException exception) {
            return orderRejected(pending, exception);
        } catch (RuntimeException exception) {
            // 원격 응답 변환은 support 가 모두 OrderShipmentException 으로 바꾼다. 여기 온 것은 예상 못 한 오류라 반영 여부를 모른다 — 행을 남긴다
            log.error("order shipment call failed unexpectedly. orderId={}, shipmentId={}, error={}", orderId, pending.id(), exception.getClass().getSimpleName());
            return failed(orderId, pending.id(), new ShipmentException(ShipmentErrorCode.ORDER_SERVICE_UNAVAILABLE));
        }
        if (confirmation.isPastShipping()) {
            // 이미 배송 완료 · 구매 확정 · 반품 등으로 지나간 주문의 멱등 200 — 반영 완료로 본다. 송장 상태를 맞추는 일은 PR-B2 다
            log.warn("order already past shipping. orderId={}, shipmentId={}, orderStatus={}", orderId, pending.id(), confirmation.orderStatus());
        }

        try {
            shipmentRegisterProcessor.confirm(pending, confirmation);
        } catch (ShipmentException exception) {
            // prepare 뒤 다른 요청이 같은 송장을 바꿨거나 지웠다. order 200 은 주문이 출고됐다는 증거라 반영 전으로 남기지 않고 지금 행에 맞춘다
            log.warn("shipment confirm lost to a concurrent change. orderId={}, shipmentId={}", orderId, pending.id());
            return reconcile(pending, confirmation, adminMemberId);
        } catch (RuntimeException exception) {
            // 주문은 배송 중이 됐고 송장만 반영 전으로 남았다 — 같은 요청 재시도가 order 멱등 200 으로 수렴한다
            log.error("shipment confirm failed after order shipped. orderId={}, shipmentId={}, error={}", orderId, pending.id(), exception.getClass().getSimpleName());
            return failed(orderId, pending.id(), new ShipmentException(ShipmentErrorCode.SHIPMENT_REGISTER_FAILED));
        }
        log.info("shipment registered. orderId={}, shipmentId={}", orderId, pending.id());
        return ShipmentRegisterResultInfo.registered(orderId, pending.id());
    }

    /**
     * 반영 완료 저장이 충돌한 뒤 지금 행에 맞춘다({@link ShipmentRegisterProcessor#reconcile}). 맞춘 송장이 이번 요청과 같은 택배사 · 송장 번호면 {@code REGISTERED},
     * 다른 요청의 송장으로 반영됐으면 {@code DELIVERY_019}("이미 다른 송장으로 출고된 주문") — 이번 요청의 송장 번호는 남지 않았고, 다시 보내도 같은 결과다.
     * 맞추기도 두 번 겹치면 {@code DELIVERY_020} 이다(같은 요청 재시도가 수렴한다).
     */
    private ShipmentRegisterResultInfo reconcile(Shipment pending, OrderShipmentConfirmation confirmation, long adminMemberId) {
        long orderId = pending.orderId();
        ShipmentException lastConflict = null;
        for (int attempt = 0; attempt < RECONCILE_ATTEMPTS; attempt++) {
            Shipment current;
            try {
                current = shipmentRegisterProcessor.reconcile(pending, confirmation, adminMemberId);
            } catch (ShipmentException exception) {
                lastConflict = exception;
                continue;
            } catch (RuntimeException exception) {
                log.error("shipment reconcile failed after order shipped. orderId={}, shipmentId={}, error={}",
                    orderId, pending.id(), exception.getClass().getSimpleName());
                return failed(orderId, pending.id(), new ShipmentException(ShipmentErrorCode.SHIPMENT_REGISTER_FAILED));
            }
            if (current.hasTracking(pending.carrier(), pending.trackingNumber())) {
                log.info("shipment registered after reconcile. orderId={}, shipmentId={}", orderId, current.id());
                return ShipmentRegisterResultInfo.registered(orderId, current.id());
            }
            log.warn("order shipped but another request's tracking was kept. orderId={}, shipmentId={}, keptShipmentId={}", orderId, pending.id(), current.id());
            return failed(orderId, null, new ShipmentException(ShipmentErrorCode.SHIPMENT_ALREADY_SHIPPED));
        }
        log.error("shipment reconcile kept conflicting after order shipped. orderId={}, shipmentId={}", orderId, pending.id());
        return failed(orderId, pending.id(), lastConflict);
    }

    /**
     * order 가 200 이 아니었다. 반영되지 않은 것이 확실하면(404 · 409 {@code ORDER_020} · 400 시각) 대기 송장을 지우고, 모르면(409 {@code ORDER_025} · 타임아웃 · 5xx · 서킷)
     * 행을 남긴다 ({@code order-flow-guide.md} §4 실패 표). 지우다 실패해도 결과는 order 의 거절 이유다 — 행은 반영 전으로 남아 재시도에서 다시 판정받는다.
     *
     * <p>400 시각 범위는 error 로그다 — 서비스 간 시계가 어긋났거나 30일 지난 재시도라 운영 확인이 필요하다. 그 밖의 거절은 업무 결과라 warn 이다
     * (계약 위반 200 · 모르는 응답은 support 가 이미 error 로 남긴다).
     */
    private ShipmentRegisterResultInfo orderRejected(Shipment pending, OrderShipmentException rejection) {
        long orderId = pending.orderId();
        ShipmentException failure = rejection.toShipmentException();
        if (rejection.getFailure() == OrderShipmentException.Failure.INVALID_TIME) {
            log.error("order rejected shippedAt. orderId={}, shipmentId={}, shippedAt={}", orderId, pending.id(), pending.shippedAt());
        } else {
            log.warn("order rejected shipment. orderId={}, shipmentId={}, failure={}", orderId, pending.id(), rejection.getFailure());
        }
        if (!rejection.getFailure().discardsPending()) {
            return failed(orderId, pending.id(), failure);
        }
        try {
            if (shipmentRegisterProcessor.discard(pending)) {
                return failed(orderId, null, failure);
            }
            log.warn("pending shipment changed before discard. orderId={}, shipmentId={}", orderId, pending.id());
        } catch (RuntimeException exception) {
            log.error("pending shipment discard failed. orderId={}, shipmentId={}, error={}", orderId, pending.id(), exception.getClass().getSimpleName());
        }
        return failed(orderId, pending.id(), failure);
    }

    private static ShipmentRegisterResultInfo failed(long orderId, Long shipmentId, ShipmentException failure) {
        return ShipmentRegisterResultInfo.failed(orderId, shipmentId, failure.getErrorCode().getCode(), failure.getMessage());
    }
}
