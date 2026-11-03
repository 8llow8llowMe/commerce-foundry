package com.commercefoundry;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand.RateCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand.ZoneCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand.RemoteAreaCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.InternationalShippingPolicyAdminUseCase;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.ShippingPolicyAdminUseCase;
import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배송비 정책 교체 감사(#163)가 실제 Facade 트랜잭션(프록시 · JPA 트랜잭션 관리자 · H2)의 <b>커밋 결과</b>로 남는지 본다 — {@code AdminAuditLog} 는
 * 트랜잭션 안의 호출을 {@code afterCompletion} 까지 미루고, 커밋됐고 업무가 성공했을 때만 {@code SUCCESS} 를 쓴다 ({@code security-guide.md} §11-4).
 * 줄에는 행위 · 행위자 · 대상 · 결과만 있고 금액 · 구간 · 국가 · 안내 문구 원문은 없다.
 *
 * <p>테스트 메서드는 트랜잭션 밖이다({@code @SpringBootTest} 기본). 애노테이션은 {@link DeliveryServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다.
 * 공유 H2 라 만든 정책은 테스트마다 지운다 — {@code DeliveryServiceSecuritySmokeTest} 는 "정책 없음" 을 전제로 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ShippingPolicyAdminAuditIntegrationTest {

    private static final long ACTOR = 4_611_686_018_427_387_904L;
    private static final String DOMESTIC_SUCCESS = "action=SHIPPING_POLICY_DOMESTIC_REPLACE actorMemberId=" + ACTOR + " target=shipping-policy:domestic result=SUCCESS";
    private static final String DOMESTIC_FAILURE = "action=SHIPPING_POLICY_DOMESTIC_REPLACE actorMemberId=" + ACTOR + " target=shipping-policy:domestic result=FAILURE";
    private static final String INTERNATIONAL_LINE = "action=SHIPPING_POLICY_INTERNATIONAL_REPLACE actorMemberId=" + ACTOR + " target=shipping-policy:international result=";

    @Autowired
    private ShippingPolicyAdminUseCase shippingPolicyAdminUseCase;

    @Autowired
    private InternationalShippingPolicyAdminUseCase internationalShippingPolicyAdminUseCase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Logger auditLogger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
    private ListAppender<ILoggingEvent> audit;

    @BeforeEach
    void attach() {
        audit = new ListAppender<>();
        audit.start();
        auditLogger.addAppender(audit);
    }

    @AfterEach
    void cleanUp() {
        auditLogger.detachAppender(audit);
        List.of("shipping_zone_rate", "shipping_zone_country", "shipping_zone", "shipping_policy_history_area", "shipping_policy_history", "shipping_remote_area",
                "shipping_policy")
            .forEach(table -> jdbcTemplate.update("delete from delivery." + table));
    }

    private static ShippingPolicyReplaceCommand domestic(long baseFee, Long version) {
        RemoteAreaCommand jeju = new RemoteAreaCommand("제주", new PostalCode("63000"), new PostalCode("63644"), Money.of(3_000, KRW));
        return new ShippingPolicyReplaceCommand(Money.of(baseFee, KRW), Money.of(50_000, KRW), List.of(jeju), version, ACTOR);
    }

    private static InternationalShippingPolicyReplaceCommand international(Long version) {
        ZoneCommand asia = new ZoneCommand("아시아", List.of("JP"), List.of(new RateCommand(500, Money.of(1_000, USD))));
        return new InternationalShippingPolicyReplaceCommand(Money.of(700, USD), Money.of(20_000, USD), "관세는 받는 분 부담", List.of(asia), version);
    }

    @Test
    @DisplayName("국내: 커밋된 교체(처음 만들기)는 SUCCESS 한 줄이고, 금액 · 구간 원문이 없다")
    void domesticCommittedIsSuccess() {
        shippingPolicyAdminUseCase.replaceDomesticPolicy(domestic(3_000, null));

        assertThat(lines()).containsExactly(DOMESTIC_SUCCESS);
        assertNoSensitiveValues();
    }

    @Test
    @DisplayName("국내: 업무 실패(정책이 없는데 version 을 보냄 · 버전 충돌)는 FAILURE 한 줄이다 — SUCCESS 는 없다")
    void domesticBusinessFailureIsFailure() {
        assertThatThrownBy(() -> shippingPolicyAdminUseCase.replaceDomesticPolicy(domestic(3_000, 5L))).isInstanceOf(DeliveryException.class);

        assertThat(lines()).containsExactly(DOMESTIC_FAILURE);
        assertNoSensitiveValues();
    }

    @Test
    @DisplayName("국내: 업무는 성공했지만 바깥 트랜잭션이 롤백되면 롤백 뒤에 FAILURE 로 남고 정책도 없다 — 롤백 전에는 줄을 쓰지 않는다")
    void domesticRolledBackIsFailure() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            shippingPolicyAdminUseCase.replaceDomesticPolicy(domestic(3_000, null));
            assertThat(audit.list).as("트랜잭션이 끝나기 전에는 줄을 쓰지 않는다").isEmpty();
            status.setRollbackOnly();
        });

        assertThat(lines()).containsExactly(DOMESTIC_FAILURE);
        assertThat(jdbcTemplate.queryForObject("select count(*) from delivery.shipping_policy", Long.class)).isZero();
    }

    @Test
    @DisplayName("해외: 커밋된 교체는 SUCCESS, 버전 충돌은 FAILURE — 요청마다 한 줄이고 금액 · 국가 · 안내 문구 원문이 없다")
    void international() {
        internationalShippingPolicyAdminUseCase.replaceInternationalPolicy(international(null), ACTOR);
        assertThatThrownBy(() -> internationalShippingPolicyAdminUseCase.replaceInternationalPolicy(international(99L), ACTOR))
            .isInstanceOf(DeliveryException.class);

        assertThat(lines()).containsExactly(INTERNATIONAL_LINE + "SUCCESS", INTERNATIONAL_LINE + "FAILURE");
        assertNoSensitiveValues();
    }

    private void assertNoSensitiveValues() {
        assertThat(lines()).allSatisfy(line -> assertThat(line)
            .doesNotContain("3000", "50000", "700", "20000", "KRW", "USD", "제주", "63000", "JP", "아시아", "관세"));
    }

    private List<String> lines() {
        return audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
