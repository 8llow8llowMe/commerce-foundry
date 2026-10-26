package com.commercefoundry.domainlayer.shippingfee.application.service;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingPolicyHistoryAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.ShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand.RemoteAreaCommand;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.ShippingPolicyAdminUseCase;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.MemberDisplayNameQueryPort;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyHistoryRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 국내 배송비 정책 교체와 교체 이력이 실제 Facade 트랜잭션 경계(프록시)에서 함께 커밋되고 함께 롤백되는지 PostgreSQL 에서 본다 (#69).
 * 테스트 메서드는 트랜잭션 밖이다({@code @SpringBootTest} 기본) — 유스케이스 호출 하나가 트랜잭션 하나다.
 *
 * <p>이력 포트는 실제 어댑터를 감싼 spy 다 — 실패 케이스만 예외를 던지게 바꾼다. 스키마는 DDL 스냅숏 + {@code ddl-auto=validate} 다.
 * Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class ShippingPolicyAdminFacadeTransactionTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/delivery-schema.sql");

    private static final long ADMIN_MEMBER_ID = 4611686018427387904L;
    private static final RemoteAreaCommand JEJU = new RemoteAreaCommand("제주", new PostalCode("63000"), new PostalCode("63644"), Money.of(3_000, KRW));

    @Autowired
    private ShippingPolicyAdminUseCase shippingPolicyAdminUseCase;

    @MockitoSpyBean
    private ShippingPolicyHistoryRepositoryPort shippingPolicyHistoryRepositoryPort;

    /** 이력 조회의 표시 이름은 auth-service 다. 이 테스트는 트랜잭션 경계만 보므로 원격 호출을 빈 맵으로 둔다. */
    @MockitoBean
    private MemberDisplayNameQueryPort memberDisplayNameQueryPort;

    @BeforeEach
    void stubDisplayNames() {
        lenient().when(memberDisplayNameQueryPort.findMaskedDisplayNames(any())).thenReturn(Map.of());
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from delivery.shipping_policy_history_area");
        jdbcTemplate.update("delete from delivery.shipping_policy_history");
        jdbcTemplate.update("delete from delivery.shipping_remote_area");
        jdbcTemplate.update("delete from delivery.shipping_policy");
    }

    private static ShippingPolicyReplaceCommand command(long baseFee, Long version) {
        return new ShippingPolicyReplaceCommand(Money.of(baseFee, KRW), null, List.of(JEJU), version, ADMIN_MEMBER_ID);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from delivery." + table, Long.class);
    }

    @Test
    @DisplayName("만들기 · 교체가 각각 이력을 한 건씩 남기고 최신순으로 읽힌다 — 교체한 어드민은 문자열 아이디다")
    void appendsHistoryPerReplace() {
        ShippingPolicyAdminResponse created = shippingPolicyAdminUseCase.replaceDomesticPolicy(command(3_000, null));
        shippingPolicyAdminUseCase.replaceDomesticPolicy(command(3_500, created.version()));

        PageResponse<ShippingPolicyHistoryAdminItem> histories = shippingPolicyAdminUseCase.getDomesticPolicyHistories(0, 20);

        assertThat(histories.totalElements()).isEqualTo(2L);
        ShippingPolicyHistoryAdminItem latest = histories.contents().getFirst();
        assertThat(latest.adminMemberId()).isEqualTo(String.valueOf(ADMIN_MEMBER_ID));
        assertThat(latest.changedAt()).isNotNull();
        assertThat(latest.before().version()).isZero();
        assertThat(latest.before().baseFee().amount()).isEqualTo(3_000L);
        assertThat(latest.after().version()).isEqualTo(1L);
        assertThat(latest.after().baseFee().amount()).isEqualTo(3_500L);
        assertThat(latest.after().remoteAreas()).extracting(ShippingPolicyHistoryAdminItem.RemoteAreaSnapshotItem::name).containsExactly("제주");
        assertThat(histories.contents().get(1).before()).isNull();
        assertThat(count("shipping_policy_history_area")).as("교체 전 1 + 교체 후 1 + 처음 만든 교체 후 1").isEqualTo(3L);
    }

    @Test
    @DisplayName("이력 저장이 실패하면 정책 교체도 롤백된다 — 금액 · 버전 · 구간이 그대로이고 이력 행이 없다")
    void historyFailureRollsBackReplace() {
        ShippingPolicyAdminResponse created = shippingPolicyAdminUseCase.replaceDomesticPolicy(command(3_000, null));
        long historiesBefore = count("shipping_policy_history");
        doThrow(new DataIntegrityViolationException("history insert failed")).when(shippingPolicyHistoryRepositoryPort).insert(any());

        assertThatThrownBy(() -> shippingPolicyAdminUseCase.replaceDomesticPolicy(command(9_000, created.version())))
            .isInstanceOf(DataIntegrityViolationException.class);

        ShippingPolicyAdminResponse current = shippingPolicyAdminUseCase.getDomesticPolicy();
        assertThat(current.version()).isEqualTo(created.version());
        assertThat(current.baseFee().amount()).isEqualTo(3_000L);
        assertThat(current.remoteAreas()).extracting(area -> area.remoteAreaId()).isEqualTo(created.remoteAreas().stream().map(area -> area.remoteAreaId()).toList());
        assertThat(count("shipping_policy_history")).isEqualTo(historiesBefore);
    }
}
