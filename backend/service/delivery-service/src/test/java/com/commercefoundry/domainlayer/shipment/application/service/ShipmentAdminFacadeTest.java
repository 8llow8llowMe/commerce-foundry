package com.commercefoundry.domainlayer.shipment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.adminaudit.application.service.processor.DeliveryAdminAuditProcessor;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item.ShipmentRegisterResultAdminItem;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentRegisterAdminResponse;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.presenter.ShipmentAdminPresenter;
import com.commercefoundry.domainlayer.shipment.application.command.ShipmentRegisterCommand;
import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException;
import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException.Failure;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentErrorCode;
import com.commercefoundry.domainlayer.shipment.application.port.out.OrderShipmentCommandPort;
import com.commercefoundry.domainlayer.shipment.application.service.processor.OrderShipmentProcessor;
import com.commercefoundry.domainlayer.shipment.application.service.processor.ShipmentQueryProcessor;
import com.commercefoundry.domainlayer.shipment.application.service.processor.ShipmentRegisterProcessor;
import com.commercefoundry.domainlayer.shipment.application.service.processor.ShipmentStatusChangeProcessor;
import com.commercefoundry.domainlayer.shipment.domain.model.CarrierCode;
import com.commercefoundry.domainlayer.shipment.domain.model.OrderShipmentConfirmation;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import com.commercefoundry.domainlayer.shipment.support.InMemoryShipmentRepository;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * 송장 등록 흐름 — {@code order-flow-guide.md} §4 실패 표 전 경로. Processor · Presenter · 감사 Processor 는 실제 객체이고, 저장소는 같은 계약의 메모리 대역,
 * order 호출만 mock 이다. 트랜잭션 경계(프록시)는 {@code ShipmentRegisterFlowTest} 가 본다.
 */
class ShipmentAdminFacadeTest {

    private static final Instant NOW = Instant.parse("2026-11-02T09:30:00Z");
    private static final long ADMIN_ID = 9L;
    private static final long ORDER_ID = 20L;
    private static final long MEMBER_ID = 30L;

    private InMemoryShipmentRepository repository;
    private OrderShipmentCommandPort orderPort;
    private MutableClock clock;
    private ShipmentAdminFacade facade;
    private ListAppender<ILoggingEvent> audit;
    private ListAppender<ILoggingEvent> facadeLog;

    @BeforeEach
    void setUp() {
        repository = new InMemoryShipmentRepository();
        orderPort = mock(OrderShipmentCommandPort.class);
        clock = new MutableClock(NOW);
        facade = facadeWith(repository);
        audit = new ListAppender<>();
        audit.start();
        ((Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME)).addAppender(audit);
        facadeLog = new ListAppender<>();
        facadeLog.start();
        ((Logger) LoggerFactory.getLogger(ShipmentAdminFacade.class)).addAppender(facadeLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME)).detachAppender(audit);
        ((Logger) LoggerFactory.getLogger(ShipmentAdminFacade.class)).detachAppender(facadeLog);
    }

    private ShipmentAdminFacade facadeWith(InMemoryShipmentRepository shipmentRepository) {
        SnowflakeIdGenerator idGenerator = new SnowflakeIdGenerator(1, 1);
        return new ShipmentAdminFacade(
            new ShipmentRegisterProcessor(shipmentRepository, idGenerator), new ShipmentStatusChangeProcessor(shipmentRepository, idGenerator),
            new ShipmentQueryProcessor(shipmentRepository), new OrderShipmentProcessor(orderPort), new DeliveryAdminAuditProcessor(), new ShipmentAdminPresenter(), clock
        );
    }

    /** 테스트가 시각을 앞으로 돌리는 시계 — 재시도 · 처리 마감을 본다. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static ShipmentRegisterCommand command(ShipmentRegisterCommand.Item... items) {
        return new ShipmentRegisterCommand(List.of(items), ADMIN_ID);
    }

    private static ShipmentRegisterCommand.Item item(long orderId, String trackingNumber) {
        return new ShipmentRegisterCommand.Item(orderId, CarrierCode.CJ_LOGISTICS, trackingNumber);
    }

    private static OrderShipmentConfirmation shipped(long orderId, String status) {
        return new OrderShipmentConfirmation(orderId, MEMBER_ID, status, ShippingScope.DOMESTIC);
    }

    private void orderAnswers(long orderId, String status) {
        willReturn(shipped(orderId, status)).given(orderPort).ship(eq(orderId), anyLong(), eq(NOW));
    }

    private void orderRejects(long orderId, Failure failure, String statusName) {
        willThrow(new OrderShipmentException(failure, statusName, null)).given(orderPort).ship(eq(orderId), anyLong(), eq(NOW));
    }

    private ShipmentRegisterResultAdminItem registerOne(String trackingNumber) {
        ShipmentRegisterAdminResponse response = facade.register(command(item(ORDER_ID, trackingNumber)));
        assertThat(response.results()).hasSize(1);
        return response.results().get(0);
    }

    private Shipment stored() {
        return repository.byOrderId(ORDER_ID).orElseThrow();
    }

    private List<String> auditLines() {
        return audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Nested
    @DisplayName("order 200")
    class Accepted {

        @Test
        @DisplayName("송장 · 등록 이력을 만들고 order 에 송장 아이디 · 등록 시각을 보낸 뒤 반영 완료(회원 · 범위 스냅숏)로 표시한다")
        void registers() {
            orderAnswers(ORDER_ID, "SHIPPING");

            ShipmentRegisterResultAdminItem result = registerOne("123456789012");

            Shipment shipment = stored();
            assertThat(result.result()).isEqualTo("REGISTERED");
            assertThat(result.deliveryId()).isEqualTo(String.valueOf(shipment.id()));
            assertThat(result.errorCode()).isNull();
            assertThat(shipment.orderSynced()).isTrue();
            assertThat(shipment.memberId()).isEqualTo(MEMBER_ID);
            assertThat(shipment.shippingScope()).isEqualTo(ShippingScope.DOMESTIC);
            assertThat(shipment.status()).isEqualTo(ShipmentStatus.SHIPPED);
            assertThat(shipment.shippedAt()).isEqualTo(NOW);
            assertThat(repository.histories()).singleElement()
                .satisfies(history -> assertThat(history.changedById()).isEqualTo(ADMIN_ID))
                .satisfies(history -> assertThat(history.shipmentId()).isEqualTo(shipment.id()));
            verify(orderPort).ship(ORDER_ID, shipment.id(), NOW);
            assertThat(auditLines()).containsExactly("action=SHIPMENT_REGISTER actorMemberId=9 target=order:20 result=SUCCESS");
        }

        @Test
        @DisplayName("응답 상태가 배송 중 다음(구매 확정 등)이어도 반영 완료로 본다 — 상태는 정보다")
        void pastShippingIsStillSynced() {
            orderAnswers(ORDER_ID, "PURCHASE_CONFIRMED");

            assertThat(registerOne("123").result()).isEqualTo("REGISTERED");
            assertThat(stored().orderSynced()).isTrue();
        }
    }

    @Nested
    @DisplayName("order 가 반영하지 않은 것이 확실하다 — 대기 송장 · 이력을 지운다")
    class Discarded {

        @Test
        @DisplayName("404 ORDER_001 → DELIVERY_014")
        void notFound() {
            orderRejects(ORDER_ID, Failure.NOT_FOUND, null);

            ShipmentRegisterResultAdminItem result = registerOne("123");

            assertThat(result).extracting("result", "deliveryId", "errorCode", "message")
                .containsExactly("FAILED", null, "DELIVERY_014", "존재하지 않는 주문입니다.");
            assertThat(repository.size()).isZero();
            assertThat(repository.histories()).isEmpty();
            assertThat(auditLines()).containsExactly("action=SHIPMENT_REGISTER actorMemberId=9 target=order:20 result=FAILURE");
        }

        @Test
        @DisplayName("409 ORDER_020 → DELIVERY_015, 메시지에 order 가 알려 준 현재 상태 이름이 들어간다")
        void notShippable() {
            orderRejects(ORDER_ID, Failure.NOT_SHIPPABLE, "취소 요청");

            ShipmentRegisterResultAdminItem result = registerOne("123");

            assertThat(result).extracting("errorCode", "message").containsExactly("DELIVERY_015", "취소 요청 상태라 출고할 수 없습니다.");
            assertThat(repository.size()).isZero();
        }

        @Test
        @DisplayName("409 ORDER_020 의 상태 이름을 읽지 못했으면 일반 문구다")
        void notShippableWithoutName() {
            orderRejects(ORDER_ID, Failure.NOT_SHIPPABLE, null);

            assertThat(registerOne("123").message()).isEqualTo("현재 주문 상태라 출고할 수 없습니다.");
        }

        @Test
        @DisplayName("400 ORDER_159~161(시각 범위) → DELIVERY_016 — 시계 차이 · 30일 지난 재시도라 건별 warn 이 아니라 error 로그다")
        void invalidTime() {
            orderRejects(ORDER_ID, Failure.INVALID_TIME, null);

            assertThat(registerOne("123").errorCode()).isEqualTo("DELIVERY_016");
            assertThat(repository.size()).isZero();
            assertThat(facadeLog.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).startsWith("order rejected shippedAt.");
            });
        }

        @Test
        @DisplayName("30일 지난 재시도 — 처음 출고 시각을 보내 order 가 ORDER_159 로 거절하면 대기 송장을 지우고, 다음 요청은 새 송장 · 그때 시각으로 등록된다")
        void expiredRetryStartsOver() {
            orderRejects(ORDER_ID, Failure.UNKNOWN, null);
            registerOne("111");
            long firstId = stored().id();
            clock.advance(Duration.ofDays(31));
            Instant later = NOW.plus(Duration.ofDays(31));
            willThrow(new OrderShipmentException(Failure.INVALID_TIME, null)).given(orderPort).ship(ORDER_ID, firstId, NOW);
            willReturn(shipped(ORDER_ID, "SHIPPING")).given(orderPort).ship(eq(ORDER_ID), anyLong(), eq(later));

            ShipmentRegisterResultAdminItem expired = registerOne("111");
            ShipmentRegisterResultAdminItem fresh = registerOne("111");

            assertThat(expired).extracting("result", "deliveryId", "errorCode").containsExactly("FAILED", null, "DELIVERY_016");
            assertThat(fresh.result()).isEqualTo("REGISTERED");
            assertThat(stored().id()).isNotEqualTo(firstId);
            assertThat(stored().shippedAt()).isEqualTo(later);
            assertThat(repository.histories()).singleElement().satisfies(history -> assertThat(history.shipmentId()).isEqualTo(stored().id()));
        }

        @Test
        @DisplayName("order 호출 사이 다른 요청이 같은 송장을 바꿨으면(버전) 지우지 않는다 — 실패 이유는 그대로, 송장 아이디는 남는다")
        void changedPendingIsKept() {
            given(orderPort.ship(eq(ORDER_ID), anyLong(), eq(NOW))).willAnswer(invocation -> {
                repository.bumpVersion(invocation.getArgument(1));
                throw new OrderShipmentException(Failure.NOT_FOUND, null);
            });

            ShipmentRegisterResultAdminItem result = registerOne("123");

            assertThat(result.errorCode()).isEqualTo("DELIVERY_014");
            assertThat(result.deliveryId()).isEqualTo(String.valueOf(stored().id()));
            assertThat(stored().orderSynced()).isFalse();
        }
    }

    @Nested
    @DisplayName("반영됐는지 모르거나 다시 보내면 되는 실패 — 송장을 남긴다")
    class Kept {

        @ParameterizedTest
        @EnumSource(value = Failure.class, names = {"CONFLICT_RETRY", "UNKNOWN"})
        @DisplayName("409 ORDER_025 → DELIVERY_017, 타임아웃 · 5xx · 서킷 → DELIVERY_018 — 반영 전 송장과 아이디가 남는다")
        void keepsPending(Failure failure) {
            orderRejects(ORDER_ID, failure, null);

            ShipmentRegisterResultAdminItem result = registerOne("123");

            assertThat(result.result()).isEqualTo("FAILED");
            assertThat(result.errorCode()).isEqualTo(failure == Failure.CONFLICT_RETRY ? "DELIVERY_017" : "DELIVERY_018");
            assertThat(result.deliveryId()).isEqualTo(String.valueOf(stored().id()));
            assertThat(stored().orderSynced()).isFalse();
            assertThat(repository.histories()).hasSize(1);
        }

        @Test
        @DisplayName("order 호출의 예상 못 한 예외도 결과 모름(DELIVERY_018)이고 송장을 남긴다")
        void unexpectedCallFailure() {
            given(orderPort.ship(eq(ORDER_ID), anyLong(), eq(NOW))).willThrow(new IllegalStateException("boom"));

            assertThat(registerOne("123").errorCode()).isEqualTo("DELIVERY_018");
            assertThat(stored().orderSynced()).isFalse();
        }

    }

    @Nested
    @DisplayName("order 200 뒤 반영 완료 저장이 충돌 — order 200 은 출고의 증거라 지금 행에 맞춘다")
    class ConfirmConflict {

        /** order 를 부르는 동안 다른 요청이 같은 주문의 송장을 바꾼 것처럼 {@code change} 를 실행하고 200 을 돌려준다. */
        private void orderAcceptsWhile(Runnable change) {
            given(orderPort.ship(eq(ORDER_ID), anyLong(), eq(NOW))).willAnswer(invocation -> {
                change.run();
                return shipped(ORDER_ID, "SHIPPING");
            });
        }

        @Test
        @DisplayName("행이 반영 전으로 남아 있으면(버전만 바뀜) 그 행을 반영 완료로 표시하고 REGISTERED 다")
        void pendingRowIsSynced() {
            orderAcceptsWhile(() -> repository.bumpVersion(stored().id()));

            ShipmentRegisterResultAdminItem result = registerOne("123");

            assertThat(result).extracting("result", "deliveryId", "errorCode").containsExactly("REGISTERED", String.valueOf(stored().id()), null);
            assertThat(stored()).extracting(Shipment::orderSynced, Shipment::memberId, Shipment::trackingNumber).containsExactly(true, MEMBER_ID, "123");
            assertThat(repository.histories()).hasSize(1);
        }

        @Test
        @DisplayName("다른 요청이 다른 송장 번호로 바꿔 둔 반영 전 행이면 그 번호를 유지한 채 반영 완료로 표시하고 DELIVERY_019 다 — 이번 번호는 남지 않았다")
        void otherTrackingIsKept() {
            orderAcceptsWhile(() -> repository.overwrite(stored().retarget(CarrierCode.HANJIN, "999")));

            ShipmentRegisterResultAdminItem result = registerOne("123");

            assertThat(result).extracting("result", "deliveryId", "errorCode").containsExactly("FAILED", null, "DELIVERY_019");
            assertThat(stored()).extracting(Shipment::orderSynced, Shipment::carrier, Shipment::trackingNumber).containsExactly(true, CarrierCode.HANJIN, "999");
            assertThat(auditLines()).containsExactly("action=SHIPMENT_REGISTER actorMemberId=9 target=order:20 result=FAILURE");
        }

        @Test
        @DisplayName("행이 없으면(다른 요청이 지움) 이번 요청 값 · 같은 송장 아이디로 반영 완료 행과 등록 이력을 다시 만들고 REGISTERED 다")
        void missingRowIsRestored() {
            long[] pendingId = new long[1];
            orderAcceptsWhile(() -> {
                pendingId[0] = stored().id();
                repository.remove(pendingId[0]);
            });

            ShipmentRegisterResultAdminItem result = registerOne("123");

            Shipment restored = stored();
            assertThat(result).extracting("result", "deliveryId").containsExactly("REGISTERED", String.valueOf(pendingId[0]));
            assertThat(restored).extracting(Shipment::id, Shipment::orderSynced, Shipment::memberId, Shipment::shippingScope, Shipment::trackingNumber, Shipment::shippedAt)
                .containsExactly(pendingId[0], true, MEMBER_ID, ShippingScope.DOMESTIC, "123", NOW);
            assertThat(repository.histories()).singleElement().satisfies(history -> {
                assertThat(history.shipmentId()).isEqualTo(pendingId[0]);
                assertThat(history.changedById()).isEqualTo(ADMIN_ID);
            });
        }

        @Test
        @DisplayName("다른 요청이 같은 송장 번호로 먼저 반영 완료했으면 그대로 두고 REGISTERED 다")
        void alreadySyncedSameTracking() {
            orderAcceptsWhile(() -> repository.overwrite(stored().markOrderSynced(MEMBER_ID, ShippingScope.DOMESTIC)));

            ShipmentRegisterResultAdminItem result = registerOne("123");

            assertThat(result.result()).isEqualTo("REGISTERED");
            assertThat(stored().orderSynced()).isTrue();
        }

        @Test
        @DisplayName("다시 만들 때 유일 키 경합이면 새 트랜잭션으로 한 번 더 읽어 맞춘다")
        void restoreRaceRereads() {
            Shipment[] competitor = new Shipment[1];
            InMemoryShipmentRepository racing = new InMemoryShipmentRepository() {
                @Override
                public Shipment insert(Shipment shipment) {
                    if (shipment.orderSynced() && competitor[0] == null) {
                        // 다시 읽은 뒤 다른 요청이 같은 주문의 송장(같은 번호)을 먼저 INSERT · 반영했다
                        competitor[0] = super.insert(Shipment.register(777L, ORDER_ID, CarrierCode.CJ_LOGISTICS, "123", NOW));
                        update(competitor[0].markOrderSynced(MEMBER_ID, null));
                        throw new OptimisticLockingFailureException("duplicate order");
                    }
                    return super.insert(shipment);
                }
            };
            ShipmentAdminFacade racingFacade = facadeWith(racing);
            given(orderPort.ship(eq(ORDER_ID), anyLong(), eq(NOW))).willAnswer(invocation -> {
                racing.remove(invocation.getArgument(1));
                return shipped(ORDER_ID, "SHIPPING");
            });

            ShipmentRegisterResultAdminItem result = racingFacade.register(command(item(ORDER_ID, "123"))).results().get(0);

            assertThat(result).extracting("result", "deliveryId").containsExactly("REGISTERED", "777");
            assertThat(racing.byOrderId(ORDER_ID).orElseThrow().orderSynced()).isTrue();
        }

        @Test
        @DisplayName("맞추기도 두 번 겹치면 DELIVERY_020 이다 — 같은 요청 재시도가 수렴한다")
        void persistentConflict() {
            InMemoryShipmentRepository conflicting = new InMemoryShipmentRepository() {
                @Override
                public Shipment update(Shipment shipment) {
                    if (shipment.orderSynced()) {
                        throw new OptimisticLockingFailureException("stale shipment");
                    }
                    return super.update(shipment);
                }
            };
            given(orderPort.ship(eq(ORDER_ID), anyLong(), eq(NOW))).willReturn(shipped(ORDER_ID, "SHIPPING"));

            ShipmentRegisterResultAdminItem result = facadeWith(conflicting).register(command(item(ORDER_ID, "123"))).results().get(0);

            assertThat(result).extracting("result", "errorCode").containsExactly("FAILED", "DELIVERY_020");
            assertThat(result.deliveryId()).isEqualTo(String.valueOf(conflicting.byOrderId(ORDER_ID).orElseThrow().id()));
        }
    }

    @Nested
    @DisplayName("재시도 · 멱등")
    class Retry {

        @Test
        @DisplayName("결과 모름 뒤 같은 주문을 다른 송장 번호로 다시 보내면 같은 송장 아이디로 번호만 바꿔 반영한다 — 출고 시각은 첫 값, 이력은 더하지 않는다")
        void retryConverges() {
            orderRejects(ORDER_ID, Failure.UNKNOWN, null);
            registerOne("111");
            long shipmentId = stored().id();
            orderAnswers(ORDER_ID, "SHIPPING");
            clock.advance(Duration.ofHours(2));

            ShipmentRegisterResultAdminItem result = registerOne("222");

            assertThat(result.result()).isEqualTo("REGISTERED");
            assertThat(result.deliveryId()).isEqualTo(String.valueOf(shipmentId));
            assertThat(stored()).extracting(Shipment::trackingNumber, Shipment::orderSynced).containsExactly("222", true);
            assertThat(stored().shippedAt()).as("재시도해도 shipped_at 은 첫 값 = order 에 보낸 값").isEqualTo(NOW);
            assertThat(repository.histories()).singleElement().satisfies(history -> assertThat(history.changedById()).isEqualTo(ADMIN_ID));
            verify(orderPort, times(2)).ship(ORDER_ID, shipmentId, NOW);
        }

        @Test
        @DisplayName("다른 관리자의 재시도도 등록 이력을 더하지 않는다 — 등록자는 처음 관리자, 재시도 관리자는 감사 줄에 남는다")
        void retryByAnotherAdmin() {
            orderRejects(ORDER_ID, Failure.UNKNOWN, null);
            registerOne("111");
            orderAnswers(ORDER_ID, "SHIPPING");

            facade.register(new ShipmentRegisterCommand(List.of(item(ORDER_ID, "111")), 77L));

            assertThat(repository.histories()).singleElement().satisfies(history -> assertThat(history.changedById()).isEqualTo(ADMIN_ID));
            assertThat(auditLines()).last().isEqualTo("action=SHIPMENT_REGISTER actorMemberId=77 target=order:20 result=SUCCESS");
        }

        @Test
        @DisplayName("반영된 송장을 같은 택배사 · 번호로 다시 보내면 ALREADY_REGISTERED 이고 order 를 부르지 않는다")
        void alreadyRegistered() {
            orderAnswers(ORDER_ID, "SHIPPING");
            registerOne("123");
            clearInvocations(orderPort);

            ShipmentRegisterResultAdminItem result = registerOne("123");

            assertThat(result).extracting("result", "deliveryId", "errorCode").containsExactly("ALREADY_REGISTERED", String.valueOf(stored().id()), null);
            verify(orderPort, never()).ship(anyLong(), anyLong(), any());
            assertThat(auditLines()).last().isEqualTo("action=SHIPMENT_REGISTER actorMemberId=9 target=order:20 result=SUCCESS");
        }

        @Test
        @DisplayName("반영된 송장을 다른 번호로 보내면 DELIVERY_019 이고 송장을 바꾸지 않는다")
        void syncedShipmentIsProtected() {
            orderAnswers(ORDER_ID, "SHIPPING");
            registerOne("123");
            Shipment before = stored();

            ShipmentRegisterResultAdminItem result = registerOne("999");

            assertThat(result).extracting("result", "deliveryId", "errorCode").containsExactly("FAILED", null, "DELIVERY_019");
            assertThat(stored()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("일괄")
    class Bulk {

        @Test
        @DisplayName("한 건의 실패가 나머지를 막지 않는다 — 결과는 요청 순서, 건수는 결과별, 감사는 주문마다 한 줄")
        void failureDoesNotBlockOthers() {
            orderAnswers(1L, "SHIPPING");
            orderRejects(2L, Failure.NOT_FOUND, null);
            orderAnswers(3L, "SHIPPING");

            ShipmentRegisterAdminResponse response = facade.register(command(item(1L, "A1"), item(2L, "A2"), item(3L, "A3")));

            assertThat(response).extracting("registeredCount", "alreadyRegisteredCount", "failedCount").containsExactly(2, 0, 1);
            assertThat(response.results()).extracting("orderId", "result", "errorCode").containsExactly(
                tuple("1", "REGISTERED", null), tuple("2", "FAILED", "DELIVERY_014"), tuple("3", "REGISTERED", null));
            assertThat(auditLines()).containsExactly(
                "action=SHIPMENT_REGISTER actorMemberId=9 target=order:1 result=SUCCESS",
                "action=SHIPMENT_REGISTER actorMemberId=9 target=order:2 result=FAILURE",
                "action=SHIPMENT_REGISTER actorMemberId=9 target=order:3 result=SUCCESS");
        }

        @Test
        @DisplayName("저장 단계의 예상 못 한 오류는 그 건만 DELIVERY_021 이고 다음 건을 계속한다 — order 를 부르지 않는다")
        void unexpectedPrepareFailure() {
            InMemoryShipmentRepository failing = new InMemoryShipmentRepository() {
                @Override
                public Optional<Shipment> findByOrderId(long orderId) {
                    if (orderId == 1L) {
                        throw new IllegalStateException("db down");
                    }
                    return super.findByOrderId(orderId);
                }
            };
            orderAnswers(2L, "SHIPPING");

            ShipmentRegisterAdminResponse response = facadeWith(failing).register(command(item(1L, "A1"), item(2L, "A2")));

            assertThat(response.results()).extracting("result", "errorCode").containsExactly(tuple("FAILED", "DELIVERY_021"), tuple("REGISTERED", null));
            verify(orderPort, never()).ship(eq(1L), anyLong(), any());
        }
    }

    @Nested
    @DisplayName("처리 마감 — 게이트웨이 응답 제한(10초) 안에 끝낸다")
    class Deadline {

        /** 주문마다 order 호출이 {@code perCall} 걸린 것처럼 시계를 돌린다. */
        private void orderAnswersTaking(Duration perCall) {
            given(orderPort.ship(anyLong(), anyLong(), any())).willAnswer(invocation -> {
                clock.advance(perCall);
                return shipped(invocation.getArgument(0), "SHIPPING");
            });
        }

        @Test
        @DisplayName("요청 시작 + 8초가 지나면 남은 건은 송장을 만들지 않고 order 를 부르지 않은 채 DELIVERY_022 다 — 감사는 주문마다 한 줄")
        void skipsAfterDeadline() {
            orderAnswersTaking(Duration.ofSeconds(3));

            ShipmentRegisterAdminResponse response = facade.register(command(item(1L, "A1"), item(2L, "A2"), item(3L, "A3"), item(4L, "A4"), item(5L, "A5")));

            assertThat(response).extracting("registeredCount", "failedCount").containsExactly(3, 2);
            assertThat(response.results()).extracting("orderId", "result", "deliveryId", "errorCode").containsExactly(
                tuple("1", "REGISTERED", String.valueOf(repository.byOrderId(1L).orElseThrow().id()), null),
                tuple("2", "REGISTERED", String.valueOf(repository.byOrderId(2L).orElseThrow().id()), null),
                tuple("3", "REGISTERED", String.valueOf(repository.byOrderId(3L).orElseThrow().id()), null),
                tuple("4", "FAILED", null, "DELIVERY_022"),
                tuple("5", "FAILED", null, "DELIVERY_022"));
            assertThat(response.results().get(3).message()).isEqualTo(ShipmentErrorCode.SHIPMENT_REGISTER_DEADLINE_EXCEEDED.getMessage());
            assertThat(repository.byOrderId(4L)).isEmpty();
            assertThat(repository.byOrderId(5L)).isEmpty();
            verify(orderPort, never()).ship(eq(4L), anyLong(), any());
            verify(orderPort, never()).ship(eq(5L), anyLong(), any());
            assertThat(auditLines()).hasSize(5).last().isEqualTo("action=SHIPMENT_REGISTER actorMemberId=9 target=order:5 result=FAILURE");
        }

        @Test
        @DisplayName("마감 시각에 딱 닿으면 시작하지 않는다 — 8초 전이면 시작한다")
        void boundary() {
            orderAnswersTaking(Duration.ofSeconds(4));

            assertThat(facade.register(command(item(1L, "A1"), item(2L, "A2"), item(3L, "A3"))).results())
                .extracting("result").containsExactly("REGISTERED", "REGISTERED", "FAILED");
        }

        @Test
        @DisplayName("마감은 요청마다 새로 잰다 — 앞 요청이 마감을 넘겨도 다음 요청은 처음부터 처리한다")
        void deadlineIsPerRequest() {
            orderAnswersTaking(Duration.ofSeconds(9));
            facade.register(command(item(1L, "A1"), item(2L, "A2")));

            assertThat(facade.register(command(item(2L, "A2"))).results()).extracting("result").containsExactly("REGISTERED");
        }
    }
}
