package com.commercefoundry.domainlayer.shipment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.adminaudit.application.service.processor.DeliveryAdminAuditProcessor;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item.ShipmentSummaryAdminItem;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentAdminResponse;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.presenter.ShipmentAdminPresenter;
import com.commercefoundry.domainlayer.shipment.application.command.ShipmentStatusChangeCommand;
import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException;
import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException.Failure;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentErrorCode;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentException;
import com.commercefoundry.domainlayer.shipment.application.port.out.OrderShipmentCommandPort;
import com.commercefoundry.domainlayer.shipment.application.service.processor.OrderShipmentProcessor;
import com.commercefoundry.domainlayer.shipment.application.service.processor.ShipmentQueryProcessor;
import com.commercefoundry.domainlayer.shipment.application.service.processor.ShipmentRegisterProcessor;
import com.commercefoundry.domainlayer.shipment.application.service.processor.ShipmentStatusChangeProcessor;
import com.commercefoundry.domainlayer.shipment.domain.model.CarrierCode;
import com.commercefoundry.domainlayer.shipment.domain.model.OrderShipmentConfirmation;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentChangedBy;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatusHistory;
import com.commercefoundry.domainlayer.shipment.support.InMemoryShipmentRepository;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * 어드민 배송 상태 변경 · 조회 (#18 PR-B2) — {@code order-flow-guide.md} §4 "배송 완료" 실패 표 전 경로. Processor · Presenter · 감사 Processor 는 실제 객체이고,
 * 저장소는 같은 계약의 메모리 대역, order 호출만 mock 이다. 트랜잭션 경계(프록시) · 실제 SQL 은 {@code ShipmentStatusFlowTest}(H2) 가 본다.
 */
class ShipmentStatusChangeFacadeTest {

    private static final Instant SHIPPED_AT = Instant.parse("2026-11-02T09:30:00Z");
    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-11-03T10:00:00.123456Z");
    private static final long ADMIN_ID = 9L;
    private static final long SHIPMENT_ID = 100L;
    private static final long ORDER_ID = 20L;
    private static final long MEMBER_ID = 30L;

    private InMemoryShipmentRepository repository;
    private OrderShipmentCommandPort orderPort;
    private ShipmentAdminFacade facade;
    private ListAppender<ILoggingEvent> audit;

    @BeforeEach
    void setUp() {
        repository = new InMemoryShipmentRepository();
        orderPort = mock(OrderShipmentCommandPort.class);
        facade = facadeWith(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        audit = new ListAppender<>();
        audit.start();
        ((Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME)).addAppender(audit);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME)).detachAppender(audit);
    }

    private ShipmentAdminFacade facadeWith(InMemoryShipmentRepository shipmentRepository, Clock clock) {
        SnowflakeIdGenerator idGenerator = new SnowflakeIdGenerator(1, 1);
        return new ShipmentAdminFacade(
            new ShipmentRegisterProcessor(shipmentRepository, idGenerator), new ShipmentStatusChangeProcessor(shipmentRepository, idGenerator),
            new ShipmentQueryProcessor(shipmentRepository), new OrderShipmentProcessor(orderPort), new DeliveryAdminAuditProcessor(), new ShipmentAdminPresenter(), clock
        );
    }

    /** 출고가 반영된 송장 + 등록 이력. */
    private Shipment syncedShipment(long id, long orderId) {
        Shipment saved = repository.insert(Shipment.register(id, orderId, CarrierCode.CJ_LOGISTICS, "123456789012", SHIPPED_AT));
        repository.insertHistory(ShipmentStatusHistory.registered(id + 1_000, id, ADMIN_ID));
        return repository.update(saved.markOrderSynced(MEMBER_ID, ShippingScope.DOMESTIC));
    }

    private Shipment stored() {
        return repository.findById(SHIPMENT_ID).orElseThrow();
    }

    private ShipmentAdminResponse change(ShipmentStatus status) {
        return facade.changeStatus(new ShipmentStatusChangeCommand(SHIPMENT_ID, status, "수령 확인", ADMIN_ID));
    }

    private ShipmentException changeFails(ShipmentStatus status) {
        try {
            change(status);
        } catch (ShipmentException exception) {
            return exception;
        }
        throw new AssertionError("ShipmentException 이 나야 한다");
    }

    private void orderAccepts(String orderStatus) {
        willReturn(new OrderShipmentConfirmation(ORDER_ID, MEMBER_ID, orderStatus, ShippingScope.DOMESTIC))
            .given(orderPort).completeDelivery(eq(ORDER_ID), eq(SHIPMENT_ID), any());
    }

    private void orderRejects(Failure failure, String statusName) {
        willThrow(new OrderShipmentException(failure, statusName, null)).given(orderPort).completeDelivery(eq(ORDER_ID), eq(SHIPMENT_ID), any());
    }

    private List<String> auditLines() {
        return audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<ShipmentStatusHistory> histories() {
        return repository.findHistories(SHIPMENT_ID);
    }

    @Nested
    @DisplayName("배송 중")
    class InTransit {

        @Test
        @DisplayName("출고 완료 → 배송 중은 delivery 안에서만 바꾼다 — order 를 부르지 않고, 관리자 · 사유 이력을 더하고, 감사 SUCCESS 다")
        void startsTransit() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);

            ShipmentAdminResponse response = change(ShipmentStatus.IN_TRANSIT);

            assertThat(response.status().code()).isEqualTo("IN_TRANSIT");
            assertThat(response.completionPending()).isFalse();
            assertThat(response.histories()).extracting(item -> item.toStatus().code(), item -> item.reason())
                .containsExactly(tuple("SHIPPED", null), tuple("IN_TRANSIT", "수령 확인"));
            assertThat(histories().getLast()).extracting(ShipmentStatusHistory::fromStatus, ShipmentStatusHistory::changedBy, ShipmentStatusHistory::changedById)
                .containsExactly(ShipmentStatus.SHIPPED, ShipmentChangedBy.ADMIN, ADMIN_ID);
            verify(orderPort, never()).completeDelivery(anyLong(), anyLong(), any());
            assertThat(auditLines()).containsExactly("action=SHIPMENT_STATUS_CHANGE actorMemberId=9 target=delivery:100 result=SUCCESS");
        }

        @Test
        @DisplayName("이미 배송 중이면 바꾸지 않고 200 — 이력 · 버전 그대로, 감사는 SUCCESS 다")
        void sameStatusIsNoop() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            change(ShipmentStatus.IN_TRANSIT);
            Shipment before = stored();

            ShipmentAdminResponse response = change(ShipmentStatus.IN_TRANSIT);

            assertThat(response.status().code()).isEqualTo("IN_TRANSIT");
            assertThat(stored()).isEqualTo(before);
            assertThat(histories()).hasSize(2);
            assertThat(auditLines()).hasSize(2).allMatch(line -> line.endsWith("result=SUCCESS"));
        }
    }

    @Nested
    @DisplayName("거절")
    class Rejected {

        @Test
        @DisplayName("없는 송장은 404 · DELIVERY_023 이고 감사 FAILURE 다")
        void notFound() {
            assertThat(changeFails(ShipmentStatus.DELIVERED).getErrorCode()).isEqualTo(ShipmentErrorCode.SHIPMENT_NOT_FOUND);
            assertThat(auditLines()).containsExactly("action=SHIPMENT_STATUS_CHANGE actorMemberId=9 target=delivery:100 result=FAILURE");
        }

        @ParameterizedTest
        @EnumSource(value = ShipmentStatus.class, names = {"IN_TRANSIT", "DELIVERED"})
        @DisplayName("출고가 주문에 반영되지 않은 송장은 409 · DELIVERY_025 이고 아무것도 바꾸지 않고 order 를 부르지 않는다")
        void unsyncedIsRejected(ShipmentStatus target) {
            repository.insert(Shipment.register(SHIPMENT_ID, ORDER_ID, CarrierCode.CJ_LOGISTICS, "1", SHIPPED_AT));
            Shipment before = stored();

            assertThat(changeFails(target).getErrorCode()).isEqualTo(ShipmentErrorCode.SHIPMENT_NOT_SYNCED);
            assertThat(stored()).isEqualTo(before);
            verify(orderPort, never()).completeDelivery(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("배송 완료 → 배송 중은 409 · DELIVERY_024 이고 메시지에 두 상태 이름이 있다")
        void transitionNotAllowed() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            orderAccepts("DELIVERED");
            change(ShipmentStatus.DELIVERED);

            ShipmentException exception = changeFails(ShipmentStatus.IN_TRANSIT);

            assertThat(exception.getErrorCode()).isEqualTo(ShipmentErrorCode.SHIPMENT_STATUS_TRANSITION_NOT_ALLOWED);
            assertThat(exception.getMessage()).isEqualTo("배송 완료 상태에서 배송 중 상태로 바꿀 수 없습니다.");
        }

        @Test
        @DisplayName("읽은 뒤 다른 요청이 먼저 바꿨으면(버전 충돌) 409 · DELIVERY_026 이고 order 를 부르지 않는다")
        void concurrentChange() {
            InMemoryShipmentRepository racing = new InMemoryShipmentRepository() {
                @Override
                public Shipment update(Shipment shipment) {
                    throw new OptimisticLockingFailureException("stale");
                }
            };
            Shipment saved = racing.insert(Shipment.register(SHIPMENT_ID, ORDER_ID, CarrierCode.CJ_LOGISTICS, "1", SHIPPED_AT));
            racing.overwrite(saved.markOrderSynced(MEMBER_ID, null));
            ShipmentAdminFacade racingFacade = facadeWith(racing, Clock.fixed(NOW, ZoneOffset.UTC));

            assertThatThrownBy(() -> racingFacade.changeStatus(new ShipmentStatusChangeCommand(SHIPMENT_ID, ShipmentStatus.DELIVERED, null, ADMIN_ID)))
                .isInstanceOfSatisfying(ShipmentException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(ShipmentErrorCode.SHIPMENT_CONCURRENTLY_CHANGED));
            verify(orderPort, never()).completeDelivery(anyLong(), anyLong(), any());
        }
    }

    @Nested
    @DisplayName("배송 완료")
    class Delivered {

        @Test
        @DisplayName("order 200 — 배송 완료 · 반영 대기 내림, 배송 완료 시각은 서버 시계(마이크로초)이고 그 값을 order 에 보낸다")
        void accepted() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            orderAccepts("DELIVERED");

            ShipmentAdminResponse response = change(ShipmentStatus.DELIVERED);

            assertThat(response.status().code()).isEqualTo("DELIVERED");
            assertThat(response.completionPending()).isFalse();
            assertThat(response.deliveredAt()).isEqualTo(NOW_MICROS);
            assertThat(stored()).extracting(Shipment::status, Shipment::completionPending, Shipment::deliveredAt)
                .containsExactly(ShipmentStatus.DELIVERED, false, NOW_MICROS);
            verify(orderPort).completeDelivery(ORDER_ID, SHIPMENT_ID, NOW_MICROS);
            assertThat(histories()).extracting(ShipmentStatusHistory::fromStatus, ShipmentStatusHistory::toStatus)
                .containsExactly(tuple(null, ShipmentStatus.SHIPPED), tuple(ShipmentStatus.SHIPPED, ShipmentStatus.DELIVERED));
            assertThat(auditLines()).containsExactly("action=SHIPMENT_STATUS_CHANGE actorMemberId=9 target=delivery:100 result=SUCCESS");
        }

        @Test
        @DisplayName("order 200 의 상태가 배송 완료가 아니어도(이미 구매 확정 등 — 멱등) 반영 완료로 본다")
        void pastDeliveredIsSynced() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            orderAccepts("PURCHASE_CONFIRMED");

            assertThat(change(ShipmentStatus.DELIVERED).completionPending()).isFalse();
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "NOT_FOUND, ORDER_NOT_FOUND, 존재하지 않는 주문입니다.",
            "NOT_SHIPPABLE, ORDER_NOT_DELIVERABLE, 취소 요청 상태라 배송 완료로 바꿀 수 없습니다.",
            "INVALID_TIME, ORDER_DELIVERED_AT_REJECTED, 배송 완료 시각을 주문에 반영하지 못했습니다. 관리자에게 문의해 주세요."
        })
        @DisplayName("반영되지 않은 것이 확실한 거절(404 · 409 ORDER_020 · 400 시각)은 직전 상태로 되돌리고 되돌림 줄(자동 처리)을 더한 뒤 오류다")
        void definiteRejectionReverts(Failure failure, ShipmentErrorCode expected, String message) {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            change(ShipmentStatus.IN_TRANSIT);
            orderRejects(failure, "취소 요청");

            ShipmentException exception = changeFails(ShipmentStatus.DELIVERED);

            assertThat(exception.getErrorCode()).isEqualTo(expected);
            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(stored()).extracting(Shipment::status, Shipment::completionPending, Shipment::deliveredAt)
                .containsExactly(ShipmentStatus.IN_TRANSIT, false, null);
            assertThat(histories()).extracting(ShipmentStatusHistory::fromStatus, ShipmentStatusHistory::toStatus, ShipmentStatusHistory::changedBy)
                .containsExactly(
                    tuple(null, ShipmentStatus.SHIPPED, ShipmentChangedBy.ADMIN),
                    tuple(ShipmentStatus.SHIPPED, ShipmentStatus.IN_TRANSIT, ShipmentChangedBy.ADMIN),
                    tuple(ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED, ShipmentChangedBy.ADMIN),
                    tuple(ShipmentStatus.DELIVERED, ShipmentStatus.IN_TRANSIT, ShipmentChangedBy.SYSTEM));
            assertThat(auditLines()).last().isEqualTo("action=SHIPMENT_STATUS_CHANGE actorMemberId=9 target=delivery:100 result=FAILURE");
        }

        @Test
        @DisplayName("상태 이름을 못 읽은 ORDER_020 은 '현재 주문 상태라 …' 다")
        void notDeliverableWithoutName() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            orderRejects(Failure.NOT_SHIPPABLE, null);

            assertThat(changeFails(ShipmentStatus.DELIVERED).getMessage()).isEqualTo("현재 주문 상태라 배송 완료로 바꿀 수 없습니다.");
            assertThat(stored().status()).isEqualTo(ShipmentStatus.SHIPPED);
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({"CONFLICT_RETRY, ORDER_CONCURRENTLY_CHANGED_ON_COMPLETION", "UNKNOWN, ORDER_SERVICE_UNAVAILABLE_ON_COMPLETION"})
        @DisplayName("결과를 모르는 거절(409 ORDER_025 · 결과 모름)은 배송 완료 · 반영 대기로 남기고 오류다")
        void unknownKeepsPending(Failure failure, ShipmentErrorCode expected) {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            orderRejects(failure, null);

            assertThat(changeFails(ShipmentStatus.DELIVERED).getErrorCode()).isEqualTo(expected);
            assertThat(stored()).extracting(Shipment::status, Shipment::completionPending, Shipment::deliveredAt)
                .containsExactly(ShipmentStatus.DELIVERED, true, NOW_MICROS);
            assertThat(histories()).hasSize(2);
        }

        @Test
        @DisplayName("order 호출의 예상 못 한 오류도 결과 모름이다 — 반영 대기로 남기고 503 · DELIVERY_030")
        void unexpectedErrorKeepsPending() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            willThrow(new IllegalStateException("boom")).given(orderPort).completeDelivery(anyLong(), anyLong(), any());

            assertThat(changeFails(ShipmentStatus.DELIVERED).getErrorCode()).isEqualTo(ShipmentErrorCode.ORDER_SERVICE_UNAVAILABLE_ON_COMPLETION);
            assertThat(stored().completionPending()).isTrue();
        }

        @Test
        @DisplayName("반영 대기의 재시도(같은 배송 완료)는 처음 배송 완료 시각을 다시 보내고, 200 이면 반영 완료 — 이력은 더하지 않는다")
        void retryResendsFirstDeliveredAt() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            orderRejects(Failure.UNKNOWN, null);
            changeFails(ShipmentStatus.DELIVERED);
            ShipmentAdminFacade later = facadeWith(repository, Clock.fixed(NOW.plusSeconds(3_600), ZoneOffset.UTC));
            orderAccepts("DELIVERED");

            ShipmentAdminResponse response = later.changeStatus(new ShipmentStatusChangeCommand(SHIPMENT_ID, ShipmentStatus.DELIVERED, null, ADMIN_ID));

            assertThat(response.completionPending()).isFalse();
            assertThat(response.deliveredAt()).isEqualTo(NOW_MICROS);
            verify(orderPort, times(2)).completeDelivery(ORDER_ID, SHIPMENT_ID, NOW_MICROS);
            assertThat(histories()).hasSize(2);
        }

        @Test
        @DisplayName("반영 대기의 재시도가 확실한 거절을 받으면 그때 되돌린다 — 다음 배송 완료는 새 시각이다")
        void retryRejectedReverts() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            orderRejects(Failure.UNKNOWN, null);
            changeFails(ShipmentStatus.DELIVERED);
            orderRejects(Failure.INVALID_TIME, null);

            assertThat(changeFails(ShipmentStatus.DELIVERED).getErrorCode()).isEqualTo(ShipmentErrorCode.ORDER_DELIVERED_AT_REJECTED);

            assertThat(stored()).extracting(Shipment::status, Shipment::deliveredAt).containsExactly(ShipmentStatus.SHIPPED, null);
            assertThat(histories().getLast().changedBy()).isEqualTo(ShipmentChangedBy.SYSTEM);
        }

        @Test
        @DisplayName("이미 반영된 배송 완료에 같은 요청은 바꾸지 않고 200 이다 — order 를 다시 부르지 않는다")
        void deliveredIsNoop() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            orderAccepts("DELIVERED");
            change(ShipmentStatus.DELIVERED);

            assertThat(change(ShipmentStatus.DELIVERED).completionPending()).isFalse();
            verify(orderPort, times(1)).completeDelivery(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("거절을 받는 사이 다른 요청이 반영을 끝냈으면 되돌리지 않는다 — 결과는 order 의 거절 이유다")
        void revertSkipsWhenChanged() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            willAnswer(invocation -> {
                repository.overwrite(stored().markCompletionSynced());
                throw new OrderShipmentException(Failure.NOT_FOUND, null);
            }).given(orderPort).completeDelivery(anyLong(), anyLong(), any());

            assertThat(changeFails(ShipmentStatus.DELIVERED).getErrorCode()).isEqualTo(ShipmentErrorCode.ORDER_NOT_FOUND);
            assertThat(stored()).extracting(Shipment::status, Shipment::completionPending).containsExactly(ShipmentStatus.DELIVERED, false);
        }

        @Test
        @DisplayName("order 200 사이 송장이 다른 배송 완료로 바뀌었으면 409 · DELIVERY_026 — 송장은 반영 대기로 남는다")
        void confirmConflict() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            willAnswer(invocation -> {
                Shipment current = stored();
                repository.overwrite(new Shipment(current.id(), current.orderId(), current.memberId(), current.carrier(), current.trackingNumber(),
                    current.status(), current.shippingScope(), true, true, current.previousStatus(), current.shippedAt(), current.deliveredAt().plusSeconds(1),
                    current.version()));
                return new OrderShipmentConfirmation(ORDER_ID, MEMBER_ID, "DELIVERED", null);
            }).given(orderPort).completeDelivery(anyLong(), anyLong(), any());

            assertThat(changeFails(ShipmentStatus.DELIVERED).getErrorCode()).isEqualTo(ShipmentErrorCode.SHIPMENT_CONCURRENTLY_CHANGED);
            assertThat(stored().completionPending()).isTrue();
        }

        @Test
        @DisplayName("반영 완료 저장이 버전 충돌로 지면 새 트랜잭션으로 다시 읽는다 — 그 사이 같은 배송 완료의 재시도가 반영을 끝냈으면 성공이다")
        void confirmLostToRetrySucceeds() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            InMemoryShipmentRepository racing = new InMemoryShipmentRepository() {
                private boolean raced;

                @Override
                public Shipment update(Shipment shipment) {
                    if (!raced && !shipment.completionPending() && shipment.status() == ShipmentStatus.DELIVERED) {
                        // 다른 요청(같은 변경의 재시도)이 먼저 반영을 끝내고 커밋했다
                        raced = true;
                        overwrite(findById(shipment.id()).orElseThrow().markCompletionSynced());
                        throw new OptimisticLockingFailureException("stale");
                    }
                    return super.update(shipment);
                }
            };
            Shipment saved = racing.insert(Shipment.register(SHIPMENT_ID, ORDER_ID, CarrierCode.CJ_LOGISTICS, "1", SHIPPED_AT));
            racing.overwrite(saved.markOrderSynced(MEMBER_ID, null));
            orderAccepts("DELIVERED");

            ShipmentAdminResponse response = facadeWith(racing, Clock.fixed(NOW, ZoneOffset.UTC))
                .changeStatus(new ShipmentStatusChangeCommand(SHIPMENT_ID, ShipmentStatus.DELIVERED, null, ADMIN_ID));

            assertThat(response.completionPending()).isFalse();
            assertThat(response.status().code()).isEqualTo("DELIVERED");
            assertThat(auditLines()).containsExactly("action=SHIPMENT_STATUS_CHANGE actorMemberId=9 target=delivery:100 result=SUCCESS");
        }

        @Test
        @DisplayName("반영 완료 저장의 예상 못 한 오류는 500 · DELIVERY_031 이고 송장은 반영 대기로 남는다(같은 변경 재시도가 수렴)")
        void confirmUnexpectedErrorIs031() {
            InMemoryShipmentRepository failing = new InMemoryShipmentRepository() {
                @Override
                public Shipment update(Shipment shipment) {
                    if (!shipment.completionPending() && shipment.status() == ShipmentStatus.DELIVERED) {
                        throw new IllegalStateException("db down");
                    }
                    return super.update(shipment);
                }
            };
            Shipment saved = failing.insert(Shipment.register(SHIPMENT_ID, ORDER_ID, CarrierCode.CJ_LOGISTICS, "1", SHIPPED_AT));
            failing.overwrite(saved.markOrderSynced(MEMBER_ID, null));
            orderAccepts("DELIVERED");

            assertThatThrownBy(() -> facadeWith(failing, Clock.fixed(NOW, ZoneOffset.UTC))
                .changeStatus(new ShipmentStatusChangeCommand(SHIPMENT_ID, ShipmentStatus.DELIVERED, null, ADMIN_ID)))
                .isInstanceOfSatisfying(ShipmentException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(ShipmentErrorCode.SHIPMENT_STATUS_CHANGE_FAILED));
            assertThat(failing.findById(SHIPMENT_ID).orElseThrow()).extracting(Shipment::status, Shipment::completionPending)
                .containsExactly(ShipmentStatus.DELIVERED, true);
            assertThat(auditLines()).containsExactly("action=SHIPMENT_STATUS_CHANGE actorMemberId=9 target=delivery:100 result=FAILURE");
        }

        @Test
        @DisplayName("상태 변경 뒤 응답용 다시 읽기가 실패하면 500 · DELIVERY_031 이다 — 바뀐 상태는 남는다")
        void detailReadFailureIs031() {
            InMemoryShipmentRepository failingRead = new InMemoryShipmentRepository() {
                @Override
                public List<ShipmentStatusHistory> findHistories(long shipmentId) {
                    throw new IllegalStateException("db down");
                }
            };
            Shipment saved = failingRead.insert(Shipment.register(SHIPMENT_ID, ORDER_ID, CarrierCode.CJ_LOGISTICS, "1", SHIPPED_AT));
            failingRead.overwrite(saved.markOrderSynced(MEMBER_ID, null));

            assertThatThrownBy(() -> facadeWith(failingRead, Clock.fixed(NOW, ZoneOffset.UTC))
                .changeStatus(new ShipmentStatusChangeCommand(SHIPMENT_ID, ShipmentStatus.IN_TRANSIT, null, ADMIN_ID)))
                .isInstanceOfSatisfying(ShipmentException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(ShipmentErrorCode.SHIPMENT_STATUS_CHANGE_FAILED));
            assertThat(failingRead.findById(SHIPMENT_ID).orElseThrow().status()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        }

        @Test
        @DisplayName("되돌림 저장이 실패하면 반영 대기로 남고, 재시도가 다시 확실한 거절을 받으면 그때 되돌린다 — 저장해 둔 직전 상태(배송 중)로")
        void revertFailureThenRetryReverts() {
            InMemoryShipmentRepository flaky = new InMemoryShipmentRepository() {
                private boolean failed;

                @Override
                public Shipment update(Shipment shipment) {
                    if (!failed && !shipment.completionPending() && shipment.status() == ShipmentStatus.IN_TRANSIT && shipment.version() != null
                        && findById(shipment.id()).orElseThrow().completionPending()) {
                        failed = true;
                        throw new IllegalStateException("db down");
                    }
                    return super.update(shipment);
                }
            };
            Shipment saved = flaky.insert(Shipment.register(SHIPMENT_ID, ORDER_ID, CarrierCode.CJ_LOGISTICS, "1", SHIPPED_AT));
            flaky.insertHistory(ShipmentStatusHistory.registered(SHIPMENT_ID + 1_000, SHIPMENT_ID, ADMIN_ID));
            flaky.overwrite(saved.markOrderSynced(MEMBER_ID, null));
            ShipmentAdminFacade flakyFacade = facadeWith(flaky, Clock.fixed(NOW, ZoneOffset.UTC));
            flakyFacade.changeStatus(new ShipmentStatusChangeCommand(SHIPMENT_ID, ShipmentStatus.IN_TRANSIT, null, ADMIN_ID));
            orderRejects(Failure.NOT_FOUND, null);

            ShipmentStatusChangeCommand deliver = new ShipmentStatusChangeCommand(SHIPMENT_ID, ShipmentStatus.DELIVERED, null, ADMIN_ID);
            assertThatThrownBy(() -> flakyFacade.changeStatus(deliver)).isInstanceOfSatisfying(ShipmentException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ShipmentErrorCode.ORDER_NOT_FOUND));
            assertThat(flaky.findById(SHIPMENT_ID).orElseThrow()).extracting(Shipment::status, Shipment::completionPending, Shipment::previousStatus)
                .containsExactly(ShipmentStatus.DELIVERED, true, ShipmentStatus.IN_TRANSIT);

            assertThatThrownBy(() -> flakyFacade.changeStatus(deliver)).isInstanceOfSatisfying(ShipmentException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ShipmentErrorCode.ORDER_NOT_FOUND));
            assertThat(flaky.findById(SHIPMENT_ID).orElseThrow())
                .extracting(Shipment::status, Shipment::completionPending, Shipment::previousStatus, Shipment::deliveredAt)
                .containsExactly(ShipmentStatus.IN_TRANSIT, false, null, null);
            assertThat(flaky.findHistories(SHIPMENT_ID)).extracting(ShipmentStatusHistory::fromStatus, ShipmentStatusHistory::toStatus, ShipmentStatusHistory::changedBy)
                .containsExactly(
                    tuple(null, ShipmentStatus.SHIPPED, ShipmentChangedBy.ADMIN),
                    tuple(ShipmentStatus.SHIPPED, ShipmentStatus.IN_TRANSIT, ShipmentChangedBy.ADMIN),
                    tuple(ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED, ShipmentChangedBy.ADMIN),
                    tuple(ShipmentStatus.DELIVERED, ShipmentStatus.IN_TRANSIT, ShipmentChangedBy.SYSTEM));
        }

        @Test
        @DisplayName("되돌린 뒤 다시 배송 완료 · 거절이 반복되면 매번 그때의 직전 상태(배송 중)로 되돌리고 이력 줄이 쌓인다")
        void repeatedRevertsKeepPreviousStatus() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            change(ShipmentStatus.IN_TRANSIT);
            orderRejects(Failure.NOT_SHIPPABLE, "취소 요청");

            changeFails(ShipmentStatus.DELIVERED);
            changeFails(ShipmentStatus.DELIVERED);

            assertThat(stored()).extracting(Shipment::status, Shipment::completionPending, Shipment::previousStatus)
                .containsExactly(ShipmentStatus.IN_TRANSIT, false, null);
            assertThat(histories()).extracting(ShipmentStatusHistory::fromStatus, ShipmentStatusHistory::toStatus).containsExactly(
                tuple(null, ShipmentStatus.SHIPPED),
                tuple(ShipmentStatus.SHIPPED, ShipmentStatus.IN_TRANSIT),
                tuple(ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED),
                tuple(ShipmentStatus.DELIVERED, ShipmentStatus.IN_TRANSIT),
                tuple(ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED),
                tuple(ShipmentStatus.DELIVERED, ShipmentStatus.IN_TRANSIT));
        }

        @Test
        @DisplayName("반영 대기 배송 완료에 배송 중 요청은 409 · DELIVERY_024 이고 order 를 다시 부르지 않는다")
        void pendingToInTransitIsRejected() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);
            orderRejects(Failure.UNKNOWN, null);
            changeFails(ShipmentStatus.DELIVERED);

            assertThat(changeFails(ShipmentStatus.IN_TRANSIT).getErrorCode()).isEqualTo(ShipmentErrorCode.SHIPMENT_STATUS_TRANSITION_NOT_ALLOWED);
            assertThat(stored()).extracting(Shipment::status, Shipment::completionPending).containsExactly(ShipmentStatus.DELIVERED, true);
            verify(orderPort, times(1)).completeDelivery(anyLong(), anyLong(), any());
        }
    }

    @Nested
    @DisplayName("사유")
    class Reason {

        @ParameterizedTest(name = "[{0}] → [{1}]")
        @CsvSource(value = {"'  수령 확인  ' | 수령 확인", "'   ' | ", "'' | "}, delimiter = '|')
        @DisplayName("앞뒤 공백을 자르고, 비면 null 로 이력에 남긴다")
        void normalizes(String raw, String expected) {
            syncedShipment(SHIPMENT_ID, ORDER_ID);

            facade.changeStatus(new ShipmentStatusChangeCommand(SHIPMENT_ID, ShipmentStatus.IN_TRANSIT, raw, ADMIN_ID));

            assertThat(histories().getLast().reason()).isEqualTo(expected);
        }

        @Test
        @DisplayName("명령의 toString 은 사유를 싣지 않는다")
        void toStringHidesReason() {
            assertThat(new ShipmentStatusChangeCommand(SHIPMENT_ID, ShipmentStatus.IN_TRANSIT, "고객 연락처 010", ADMIN_ID).toString()).doesNotContain("010");
        }
    }

    @Nested
    @DisplayName("조회")
    class Query {

        @Test
        @DisplayName("상세 — 아이디는 문자열, 코드 값은 code · name · description, 이력은 시간순이다. 없으면 DELIVERY_023")
        void detail() {
            syncedShipment(SHIPMENT_ID, ORDER_ID);

            ShipmentAdminResponse response = facade.getDetail(SHIPMENT_ID);

            assertThat(response.deliveryId()).isEqualTo("100");
            assertThat(response.orderId()).isEqualTo("20");
            assertThat(response.carrier().name()).isEqualTo("CJ대한통운");
            assertThat(response.status().code()).isEqualTo("SHIPPED");
            assertThat(response.orderSynced()).isTrue();
            assertThat(response.histories()).singleElement().satisfies(history -> {
                assertThat(history.fromStatus()).isNull();
                assertThat(history.changedBy().code()).isEqualTo("ADMIN");
                assertThat(history.changedById()).isEqualTo("9");
            });
            assertThatThrownBy(() -> facade.getDetail(999L)).isInstanceOfSatisfying(ShipmentException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ShipmentErrorCode.SHIPMENT_NOT_FOUND));
        }

        @Test
        @DisplayName("목록 synced=false 는 출고 반영 전과 배송 완료 반영 대기를 함께 고른다 — 최신순")
        void unsyncedFilter() {
            repository.insert(Shipment.register(1L, 11L, CarrierCode.DHL, "1", SHIPPED_AT));
            syncedShipment(2L, 12L);
            Shipment pendingCompletion = syncedShipment(3L, 13L);
            repository.update(pendingCompletion.deliver(NOW));

            PageResponse<ShipmentSummaryAdminItem> unsynced = facade.search(null, null, false, 0, 20);
            PageResponse<ShipmentSummaryAdminItem> synced = facade.search(null, null, true, 0, 20);

            assertThat(unsynced.contents()).extracting(ShipmentSummaryAdminItem::deliveryId).containsExactly("3", "1");
            assertThat(unsynced.totalElements()).isEqualTo(2);
            assertThat(synced.contents()).extracting(ShipmentSummaryAdminItem::deliveryId).containsExactly("2");
            assertThat(facade.search(12L, null, null, 0, 20).contents()).singleElement().extracting(ShipmentSummaryAdminItem::orderId).isEqualTo("12");
        }
    }
}
