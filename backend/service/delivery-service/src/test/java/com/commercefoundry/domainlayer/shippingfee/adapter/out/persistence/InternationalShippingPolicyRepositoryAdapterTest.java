package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.CUSTOMS_NOTICE;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.INTERNATIONAL_POLICY_ID;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.asia;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.northAmerica;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.usd;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneCountryEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneRateEntity;
import com.commercefoundry.domainlayer.shippingfee.application.mapper.InternationalShippingPolicyMapperImpl;
import com.commercefoundry.domainlayer.shippingfee.application.mapper.ShippingPolicyMapperImpl;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.InternationalShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.InternationalShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZone;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZoneCountry;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZoneRate;
import com.commercefoundry.global.config.DeliveryServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
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
 * 해외 배송비 정책 영속성 어댑터를 실제 PostgreSQL 에 질의해 본다 — 파생 쿼리 · {@code in} 조회 · 서브쿼리 벌크 DELETE · 조건부 UPDATE 는
 * 컴파일로 검증되지 않는다 ({@code coding-conventions.md} §9-5). 구성은 국내 {@link ShippingPolicyRepositoryAdapterTest} 와 같다.
 *
 * <p>제약 위반은 테스트 하나에 하나만 낸다 — 테스트마다 한 트랜잭션이고 PostgreSQL 은 첫 위반 뒤 그 트랜잭션을 중단한다
 * ({@code bootstrap-conventions.md} 리포지토리 테스트).
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
@Import({
    QuerydslConfigurer.class, DeliveryServiceFeaturesConfig.class,
    InternationalShippingPolicyRepositoryAdapter.class, InternationalShippingPolicyMapperImpl.class,
    ShippingPolicyRepositoryAdapter.class, ShippingPolicyMapperImpl.class
})
class InternationalShippingPolicyRepositoryAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/delivery-schema.sql");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private InternationalShippingPolicyRepositoryPort internationalShippingPolicyRepositoryPort;

    @Autowired
    private ShippingPolicyRepositoryPort shippingPolicyRepositoryPort;

    private static InternationalShippingPolicy newPolicy(List<ShippingZone> zones) {
        return new InternationalShippingPolicy(INTERNATIONAL_POLICY_ID, usd(500), usd(20_000), CUSTOMS_NOTICE, zones, null, null);
    }

    private static InternationalShippingPolicy replacing(InternationalShippingPolicy current, String customsNotice, List<ShippingZone> zones) {
        return new InternationalShippingPolicy(current.id(), current.baseFee(), current.freeShippingThreshold(), customsNotice, zones, current.version(), null);
    }

    private long count(Class<?> entityClass) {
        return entityManager.getEntityManager().createQuery("select count(e) from " + entityClass.getSimpleName() + " e", Long.class).getSingleResult();
    }

    @Test
    @DisplayName("정책이 없으면 빈 값이다")
    void emptyWhenAbsent() {
        assertThat(internationalShippingPolicyRepositoryPort.findPolicy()).isEmpty();
    }

    @Test
    @DisplayName("버전 없이 교체하면 만든다 — 버전 0, 그룹은 표시 순서 · 국가는 코드 순 · 구간은 상한 순으로 다시 읽힌다")
    void createsPolicy() {
        InternationalShippingPolicy created = internationalShippingPolicyRepositoryPort.replace(newPolicy(List.of(northAmerica(), asia())));

        assertThat(created.version()).isZero();
        assertThat(created.updatedAt()).isNotNull();
        assertThat(created.customsNotice()).isEqualTo(CUSTOMS_NOTICE);
        assertThat(created.zones()).extracting(ShippingZone::name).containsExactly("북미", "아시아");
        assertThat(created.zones().get(1).countries()).extracting(ShippingZoneCountry::countryCode).containsExactly("JP", "SG");
        assertThat(created.zones().get(1).rates()).extracting(ShippingZoneRate::maxWeightGrams).containsExactly(500, 1_000, 2_000);

        entityManager.clear();
        assertThat(internationalShippingPolicyRepositoryPort.findPolicy()).hasValue(created);
    }

    @Test
    @DisplayName("국내 정책과 한 테이블에 나란히 있고, 서로의 조회에 섞이지 않는다")
    void coexistsWithDomestic() {
        shippingPolicyRepositoryPort.replace(new ShippingPolicy(7001L, ShippingScope.DOMESTIC, krw(3_000), null, List.of(), null, null));
        internationalShippingPolicyRepositoryPort.replace(newPolicy(List.of(asia())));

        entityManager.clear();
        assertThat(shippingPolicyRepositoryPort.findDomesticPolicy()).hasValueSatisfying(policy -> assertThat(policy.id()).isEqualTo(7001L));
        assertThat(internationalShippingPolicyRepositoryPort.findPolicy()).hasValueSatisfying(policy -> assertThat(policy.id()).isEqualTo(INTERNATIONAL_POLICY_ID));
    }

    @Test
    @DisplayName("조회한 버전으로 교체하면 하위 행을 통째로 갈아 끼우고 버전이 1 오른다 — 안내 문구만 바꿔도 오른다")
    void replacesPolicy() {
        InternationalShippingPolicy created = internationalShippingPolicyRepositoryPort.replace(newPolicy(List.of(asia(), northAmerica())));
        ShippingZone europe = new ShippingZone(9101L, "유럽", List.of(new ShippingZoneCountry(9102L, "FR")), List.of(new ShippingZoneRate(9103L, 1_000, usd(3_000))));

        InternationalShippingPolicy replaced = internationalShippingPolicyRepositoryPort.replace(replacing(created, null, List.of(europe)));

        assertThat(replaced.version()).isEqualTo(1L);
        assertThat(replaced.customsNotice()).isNull();
        assertThat(replaced.zones()).containsExactly(europe);
        assertThat(count(ShippingZoneCountryEntity.class)).as("옛 국가는 지워졌다").isEqualTo(1L);
        assertThat(count(ShippingZoneRateEntity.class)).as("옛 구간은 지워졌다").isEqualTo(1L);
    }

    @Test
    @DisplayName("오래된 버전으로 교체하면 낙관적 락 예외다")
    void staleVersionIsRejected() {
        InternationalShippingPolicy created = internationalShippingPolicyRepositoryPort.replace(newPolicy(List.of(asia())));
        internationalShippingPolicyRepositoryPort.replace(replacing(created, CUSTOMS_NOTICE, List.of(northAmerica())));

        assertThatThrownBy(() -> internationalShippingPolicyRepositoryPort.replace(replacing(created, null, List.of(asia()))))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("해외 정책이 이미 있는데 새로 만들면 낙관적 락 예외다 (uk_shipping_policy_scope)")
    void duplicateCreateIsRejected() {
        internationalShippingPolicyRepositoryPort.replace(newPolicy(List.of(asia())));
        InternationalShippingPolicy another = new InternationalShippingPolicy(INTERNATIONAL_POLICY_ID + 1, usd(500), null, null, List.of(), null, null);

        assertThatThrownBy(() -> internationalShippingPolicyRepositoryPort.replace(another))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class)
            .rootCause()
            .hasMessageContaining("uk_shipping_policy_scope");
    }

    @Test
    @DisplayName("제약: 한 정책에서 한 국가는 한 그룹에만 (uk_shipping_zone_country_shipping_policy_id_country_code)")
    void countryUniquePerPolicy() {
        entityManager.persistAndFlush(ShippingZoneCountryEntity.builder().id(1L).shippingPolicyId(INTERNATIONAL_POLICY_ID).shippingZoneId(10L).countryCode("JP").build());

        assertThatThrownBy(() -> entityManager.persistAndFlush(
            ShippingZoneCountryEntity.builder().id(2L).shippingPolicyId(INTERNATIONAL_POLICY_ID).shippingZoneId(20L).countryCode("JP").build()))
            .rootCause()
            .hasMessageContaining("uk_shipping_zone_country_shipping_policy_id_country_code");
    }

    @Test
    @DisplayName("제약: KR 은 해외 그룹 국가로 저장되지 않는다 (ck_shipping_zone_country_country_code_not_domestic)")
    void domesticCountryCodeIsRejected() {
        assertThatThrownBy(() -> entityManager.persistAndFlush(
            ShippingZoneCountryEntity.builder().id(1L).shippingPolicyId(INTERNATIONAL_POLICY_ID).shippingZoneId(10L).countryCode("KR").build()))
            .rootCause()
            .hasMessageContaining("ck_shipping_zone_country_country_code_not_domestic");
    }

    @Test
    @DisplayName("제약: 소문자 국가 코드는 저장되지 않는다 (ck_shipping_zone_country_country_code_format)")
    void lowercaseCountryCodeIsRejected() {
        assertThatThrownBy(() -> entityManager.persistAndFlush(
            ShippingZoneCountryEntity.builder().id(1L).shippingPolicyId(INTERNATIONAL_POLICY_ID).shippingZoneId(10L).countryCode("jp").build()))
            .rootCause()
            .hasMessageContaining("ck_shipping_zone_country_country_code_format");
    }

    @Test
    @DisplayName("제약: 상한 0g 구간은 저장되지 않는다 (ck_shipping_zone_rate_max_weight_grams_positive)")
    void zeroMaxWeightIsRejected() {
        assertThatThrownBy(() -> entityManager.persistAndFlush(
            ShippingZoneRateEntity.builder().id(1L).shippingZoneId(10L).maxWeightGrams(0).feeAmount(1_000L).feeCurrency(USD).build()))
            .rootCause()
            .hasMessageContaining("ck_shipping_zone_rate_max_weight_grams_positive");
    }

    @Test
    @DisplayName("제약: 한 그룹의 같은 상한은 저장되지 않는다 (uk_shipping_zone_rate_shipping_zone_id_max_weight_grams)")
    void duplicateMaxWeightIsRejected() {
        entityManager.persistAndFlush(ShippingZoneRateEntity.builder().id(2L).shippingZoneId(10L).maxWeightGrams(500).feeAmount(1_000L).feeCurrency(USD).build());
        assertThatThrownBy(() -> entityManager.persistAndFlush(
            ShippingZoneRateEntity.builder().id(3L).shippingZoneId(10L).maxWeightGrams(500).feeAmount(2_000L).feeCurrency(USD).build()))
            .rootCause()
            .hasMessageContaining("uk_shipping_zone_rate_shipping_zone_id_max_weight_grams");
    }

    @Test
    @DisplayName("제약: 국내 정책 행에는 관세 안내 문구를 저장하지 않는다 (ck_shipping_policy_customs_notice_scope)")
    void customsNoticeOnlyInternational() {
        ShippingPolicyEntity entity = ShippingPolicyEntity.builder()
            .id(7001L).scope(ShippingScope.DOMESTIC).baseFeeAmount(3_000L).baseFeeCurrency(KRW)
            .customsNotice(CUSTOMS_NOTICE)
            .build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(entity))
            .rootCause()
            .hasMessageContaining("ck_shipping_policy_customs_notice_scope");
    }
}
