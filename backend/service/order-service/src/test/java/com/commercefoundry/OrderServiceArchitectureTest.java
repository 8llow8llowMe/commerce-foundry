package com.commercefoundry;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.commercefoundry.common.support.HexagonalArchitectureRules;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimCancellationVoidProcessor;
import com.commercefoundry.security.support.ControllerAuthorizationArchTestSupport;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 컨트롤러 인가 선언 · 전역 Feign 설정 · 헥사고날 계층 의존 정적 규칙 — 이 서비스의 main 클래스만 본다.
 * 규칙과 위반 시 고치는 법은 {@link ControllerAuthorizationArchTestSupport}(인가 R0~R7) · {@link HexagonalArchitectureRules}(계층 H1~H9).
 * {@code applicationClass()} 는 인터페이스 메서드도 구현하므로 public 이다. 이 서비스 전용 규칙은 아래 {@code @Test} 로 둔다.
 */
class OrderServiceArchitectureTest extends ControllerAuthorizationArchTestSupport implements HexagonalArchitectureRules {

    @Override
    public Class<?> applicationClass() {
        return OrderServiceApplication.class;
    }

    @Test
    @DisplayName("payment 무환불 판정(PaymentCancelPort#confirmNoRefund)을 부르는 main 클래스는 ClaimCancellationVoidProcessor 하나다 — 상세 조회 · 스케줄러 · 고객 요청은 부르지 않는다 (#17 A4)")
    void onlyCancellationVoidProcessorCallsConfirmNoRefund() {
        // 이름 · 소유자로 맞춘다 — 포트 시그니처가 바뀌어도 규칙이 빈 조건으로 통과하지 않는다
        DescribedPredicate<JavaMethodCall> confirmNoRefund = new DescribedPredicate<>("call PaymentCancelPort.confirmNoRefund") {
            @Override
            public boolean test(JavaMethodCall call) {
                return "confirmNoRefund".equals(call.getTarget().getName()) && call.getTargetOwner().isAssignableTo(PaymentCancelPort.class);
            }
        };
        noClasses().that().doNotHaveFullyQualifiedName(ClaimCancellationVoidProcessor.class.getName())
            .should().callMethodWhere(confirmNoRefund)
            .because("payment 무환불 판정은 어드민 취소 거절 동작 1회당 1회만 부른다 — 자동 재시도 · 조회 경로에 붙이지 않는다(payment-guide.md §8-1)")
            .check(hexagonalTargetClasses());
    }
}
