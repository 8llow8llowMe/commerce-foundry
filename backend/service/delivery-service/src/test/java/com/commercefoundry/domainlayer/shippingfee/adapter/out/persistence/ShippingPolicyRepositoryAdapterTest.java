package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.jeju;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.remoteArea;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.ulleung;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingRemoteAreaEntity;
import com.commercefoundry.domainlayer.shippingfee.application.mapper.ShippingPolicyMapperImpl;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.RemoteArea;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import com.commercefoundry.global.config.DeliveryServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 배송비 정책 영속성 어댑터를 실제 PostgreSQL 에 질의해 본다 — 파생 쿼리 · JPQL 조건부 UPDATE · 벌크 DELETE 는 컴파일로 검증되지 않는다
 * ({@code coding-conventions.md} §9-5).
 *
 * <p>스키마는 DDL 스냅숏을 init script 로 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한다. 컨텍스트가 뜨면 스냅숏과 매핑이 맞는 것이다.
 * Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다 ({@link EnabledWithDockerOrOnCi}).
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=delivery",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, DeliveryServiceFeaturesConfig.class, ShippingPolicyRepositoryAdapter.class, ShippingPolicyMapperImpl.class})
class ShippingPolicyRepositoryAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/delivery-schema.sql");

    private static final long POLICY_ID = 7001L;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ShippingPolicyRepositoryPort shippingPolicyRepositoryPort;

    private static ShippingPolicy newPolicy(Money baseFee, Money threshold, List<RemoteArea> remoteAreas) {
        return new ShippingPolicy(POLICY_ID, ShippingScope.DOMESTIC, baseFee, threshold, remoteAreas, null, null);
    }

    private static ShippingPolicy replacing(ShippingPolicy current, Money baseFee, Money threshold, List<RemoteArea> remoteAreas) {
        return new ShippingPolicy(current.id(), current.scope(), baseFee, threshold, remoteAreas, current.version(), null);
    }

    private long remoteAreaRowCount() {
        return entityManager.getEntityManager().createQuery("select count(a) from ShippingRemoteAreaEntity a", Long.class).getSingleResult();
    }

    @Test
    @DisplayName("정책이 없으면 빈 값이다")
    void emptyWhenAbsent() {
        assertThat(shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC)).isEmpty();
    }

    @Test
    @DisplayName("버전 없이 교체하면 만든다 — 버전 0 · 수정 시각이 채워지고 구간은 시작 우편번호 순으로 다시 읽힌다")
    void createsPolicy() {
        ShippingPolicy created = shippingPolicyRepositoryPort.replace(newPolicy(krw(3_000), krw(100_000), List.of(jeju(), ulleung())));

        assertThat(created.id()).isEqualTo(POLICY_ID);
        assertThat(created.version()).isZero();
        assertThat(created.updatedAt()).isNotNull();
        assertThat(created.freeShippingThreshold()).isEqualTo(krw(100_000));
        assertThat(created.remoteAreas()).extracting(RemoteArea::id, RemoteArea::name).containsExactly(tuple(7102L, "울릉"), tuple(7101L, "제주"));

        entityManager.clear();
        assertThat(shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC)).hasValue(created);
    }

    @Test
    @DisplayName("조회한 버전으로 교체하면 금액을 바꾸고 구간을 통째로 갈아 끼우며 버전이 1 오른다")
    void replacesPolicy() {
        ShippingPolicy created = shippingPolicyRepositoryPort.replace(newPolicy(krw(3_000), krw(100_000), List.of(jeju(), ulleung())));
        RemoteArea newJeju = remoteArea(7201L, "제주(개편)", "63000", "63644", krw(4_000));

        ShippingPolicy replaced = shippingPolicyRepositoryPort.replace(replacing(created, krw(2_500), null, List.of(newJeju)));

        assertThat(replaced.version()).isEqualTo(1L);
        assertThat(replaced.baseFee()).isEqualTo(krw(2_500));
        assertThat(replaced.freeShippingThreshold()).isNull();
        assertThat(replaced.remoteAreas()).containsExactly(newJeju);
        assertThat(replaced.updatedAt()).isAfterOrEqualTo(created.updatedAt());
        assertThat(remoteAreaRowCount()).as("옛 구간은 지워졌다").isEqualTo(1L);
    }

    /** 금액이 그대로면 엔티티 변경 감지로는 UPDATE 가 나가지 않는다 — 조건부 UPDATE 를 쓴 이유다. */
    @Test
    @DisplayName("구간만 바꿔도 버전이 오른다")
    void versionIncrementsWhenOnlyAreasChange() {
        ShippingPolicy created = shippingPolicyRepositoryPort.replace(newPolicy(krw(3_000), krw(100_000), List.of(jeju())));

        ShippingPolicy replaced = shippingPolicyRepositoryPort.replace(replacing(created, krw(3_000), krw(100_000), List.of(ulleung())));

        assertThat(replaced.version()).isEqualTo(1L);
        assertThat(replaced.remoteAreas()).extracting(RemoteArea::name).containsExactly("울릉");
    }

    @Test
    @DisplayName("오래된 버전으로 교체하면 낙관적 락 예외이고 정책 · 구간이 그대로다")
    void staleVersionIsRejected() {
        ShippingPolicy created = shippingPolicyRepositoryPort.replace(newPolicy(krw(3_000), krw(100_000), List.of(jeju())));
        ShippingPolicy replaced = shippingPolicyRepositoryPort.replace(replacing(created, krw(3_500), krw(100_000), List.of(jeju())));

        assertThatThrownBy(() -> shippingPolicyRepositoryPort.replace(replacing(created, krw(9_000), null, List.of())))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        entityManager.clear();
        assertThat(shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC)).hasValueSatisfying(current -> {
            assertThat(current.version()).isEqualTo(replaced.version());
            assertThat(current.baseFee()).isEqualTo(krw(3_500));
            assertThat(current.remoteAreas()).extracting(RemoteArea::name).containsExactly("제주");
        });
    }

    @Test
    @DisplayName("같은 범위의 정책이 이미 있는데 새로 만들면 낙관적 락 예외다 (uk_shipping_policy_scope)")
    void duplicateCreateIsRejected() {
        shippingPolicyRepositoryPort.replace(newPolicy(krw(3_000), null, List.of()));
        ShippingPolicy another = new ShippingPolicy(POLICY_ID + 1, ShippingScope.DOMESTIC, krw(3_000), null, List.of(), null, null);

        assertThatThrownBy(() -> shippingPolicyRepositoryPort.replace(another))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class)
            .rootCause()
            .hasMessageContaining("uk_shipping_policy_scope");
    }

    @Test
    @DisplayName("제약: 무료배송 기준 0원은 저장되지 않는다 (ck_shipping_policy_free_shipping_threshold_positive)")
    void zeroThresholdIsRejected() {
        ShippingPolicyEntity entity = ShippingPolicyEntity.builder()
            .id(POLICY_ID).scope(ShippingScope.DOMESTIC).baseFeeAmount(3_000L).baseFeeCurrency(KRW)
            .freeShippingThresholdAmount(0L).freeShippingThresholdCurrency(KRW)
            .build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(entity))
            .rootCause()
            .hasMessageContaining("ck_shipping_policy_free_shipping_threshold_positive");
    }

    @Test
    @DisplayName("제약: 무료배송 기준 금액만 있고 통화가 없으면 저장되지 않는다 (ck_shipping_policy_free_shipping_threshold_pair)")
    void halfThresholdIsRejected() {
        ShippingPolicyEntity entity = ShippingPolicyEntity.builder()
            .id(POLICY_ID).scope(ShippingScope.DOMESTIC).baseFeeAmount(3_000L).baseFeeCurrency(KRW).freeShippingThresholdAmount(50_000L)
            .build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(entity))
            .rootCause()
            .hasMessageContaining("ck_shipping_policy_free_shipping_threshold_pair");
    }

    @Test
    @DisplayName("제약: 시작 우편번호가 끝보다 큰 구간은 저장되지 않는다 (ck_shipping_remote_area_postal_code_range)")
    void reversedRangeIsRejected() {
        ShippingRemoteAreaEntity entity = ShippingRemoteAreaEntity.builder()
            .id(7101L).shippingPolicyId(POLICY_ID).name("거꾸로").postalCodeFrom("63644").postalCodeTo("63000").surchargeAmount(3_000L).surchargeCurrency(KRW)
            .build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(entity))
            .rootCause()
            .hasMessageContaining("ck_shipping_remote_area_postal_code_range");
    }

    @Test
    @DisplayName("제약: 음수 추가 배송비는 저장되지 않는다 (ck_shipping_remote_area_surcharge_non_negative)")
    void negativeSurchargeIsRejected() {
        ShippingRemoteAreaEntity entity = ShippingRemoteAreaEntity.builder()
            .id(7101L).shippingPolicyId(POLICY_ID).name("제주").postalCodeFrom("63000").postalCodeTo("63644").surchargeAmount(-1L).surchargeCurrency(KRW)
            .build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(entity))
            .rootCause()
            .hasMessageContaining("ck_shipping_remote_area_surcharge_non_negative");
    }
}
