package com.commercefoundry.domainlayer.shippingaddress.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.commercefoundry.domainlayer.shippingaddress.adapter.out.persistence.entity.ShippingAddressEntity;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressErrorCode;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressException;
import com.commercefoundry.domainlayer.shippingaddress.application.mapper.ShippingAddressMapperImpl;
import com.commercefoundry.domainlayer.shippingaddress.application.port.out.ShippingAddressRepositoryPort;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddress;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddressContent;
import com.commercefoundry.global.config.AuthServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL 컨테이너에 DDL 스냅숏을 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한 뒤 JPQL · 파생 쿼리 · 제약을 실제로 확인한다
 * ({@code bootstrap-conventions.md} "리포지토리 테스트"). Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다.
 * 동시 요청은 {@code ShippingAddressConcurrencyTest} 가 본다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=auth",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({
    AuthServiceFeaturesConfig.class,
    QuerydslConfigurer.class,
    ShippingAddressMapperImpl.class,
    ShippingAddressRepositoryAdapter.class
})
class ShippingAddressPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/auth-schema.sql");

    private static final long MEMBER_ID = 1L;
    private static final long OTHER_MEMBER_ID = 2L;
    private static final ShippingAddressContent DOMESTIC =
        ShippingAddressContent.of("집", "홍길동", "010-1234-5678", "KR", "06236", "서울특별시 강남구 테헤란로 123", "4층", null, null);
    private static final ShippingAddressContent INTERNATIONAL =
        ShippingAddressContent.of(null, "John Doe", "+1 415-555-0100", "US", null, "1 Market St", null, "San Francisco", "CA");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ShippingAddressRepositoryPort shippingAddressRepositoryPort;

    @Test
    @DisplayName("새 주소를 넣으면 버전 0 · 감사 시각이 채워지고, 기본 여부는 default_member_id 로 저장된다")
    void insertAndFind() {
        shippingAddressRepositoryPort.save(ShippingAddress.create(10L, MEMBER_ID, DOMESTIC, true));
        shippingAddressRepositoryPort.save(ShippingAddress.create(11L, MEMBER_ID, INTERNATIONAL, false));
        entityManager.clear();

        assertThat(shippingAddressRepositoryPort.findByIdAndMemberId(10L, MEMBER_ID))
            .contains(new ShippingAddress(10L, MEMBER_ID, DOMESTIC, true, 0L));
        assertThat(shippingAddressRepositoryPort.findByIdAndMemberId(11L, MEMBER_ID))
            .contains(new ShippingAddress(11L, MEMBER_ID, INTERNATIONAL, false, 0L));
        ShippingAddressEntity entity = entityManager.find(ShippingAddressEntity.class, 10L);
        assertThat(entity.getDefaultMemberId()).isEqualTo(MEMBER_ID);
        assertThat(entityManager.find(ShippingAddressEntity.class, 11L).getDefaultMemberId()).isNull();
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("다른 회원 아이디로는 찾지 못한다 — 소유 확인은 (주소 아이디, 회원 아이디) 다")
    void findRequiresOwner() {
        shippingAddressRepositoryPort.save(ShippingAddress.create(10L, MEMBER_ID, DOMESTIC, true));
        entityManager.clear();

        assertThat(shippingAddressRepositoryPort.findByIdAndMemberId(10L, OTHER_MEMBER_ID)).isEmpty();
        assertThat(shippingAddressRepositoryPort.deleteByIdAndMemberId(10L, OTHER_MEMBER_ID)).isFalse();
        assertThat(shippingAddressRepositoryPort.findByIdAndMemberId(10L, MEMBER_ID)).isPresent();
    }

    @Test
    @DisplayName("목록은 기본 배송지가 먼저, 그다음 최신순(아이디 내림차순)이다 — 기본이 가장 옛 주소여도 맨 앞")
    void listOrder() {
        shippingAddressRepositoryPort.save(ShippingAddress.create(10L, MEMBER_ID, DOMESTIC, true));
        shippingAddressRepositoryPort.save(ShippingAddress.create(12L, MEMBER_ID, DOMESTIC, false));
        shippingAddressRepositoryPort.save(ShippingAddress.create(11L, MEMBER_ID, DOMESTIC, false));
        shippingAddressRepositoryPort.save(ShippingAddress.create(13L, OTHER_MEMBER_ID, DOMESTIC, true));
        entityManager.clear();

        assertThat(shippingAddressRepositoryPort.findAllByMemberId(MEMBER_ID)).extracting(ShippingAddress::id).containsExactly(10L, 12L, 11L);
        assertThat(shippingAddressRepositoryPort.countByMemberId(MEMBER_ID)).isEqualTo(3L);
        assertThat(shippingAddressRepositoryPort.findLatestByMemberId(MEMBER_ID)).hasValueSatisfying(latest -> assertThat(latest.id()).isEqualTo(12L));
        assertThat(shippingAddressRepositoryPort.findAllByMemberId(3L)).isEmpty();
        assertThat(shippingAddressRepositoryPort.findLatestByMemberId(3L)).isEmpty();
    }

    @Test
    @DisplayName("기본 교체 — 해제 UPDATE 후 지정 UPDATE. 두 행 모두 버전이 오르고 다른 회원의 기본은 그대로다")
    void moveDefault() {
        shippingAddressRepositoryPort.save(ShippingAddress.create(10L, MEMBER_ID, DOMESTIC, true));
        shippingAddressRepositoryPort.save(ShippingAddress.create(11L, MEMBER_ID, DOMESTIC, false));
        shippingAddressRepositoryPort.save(ShippingAddress.create(20L, OTHER_MEMBER_ID, DOMESTIC, true));
        entityManager.clear();

        shippingAddressRepositoryPort.clearDefault(MEMBER_ID);
        shippingAddressRepositoryPort.markDefault(11L, MEMBER_ID);
        entityManager.clear();

        assertThat(shippingAddressRepositoryPort.findByIdAndMemberId(10L, MEMBER_ID))
            .hasValueSatisfying(address -> assertThat(address).extracting(ShippingAddress::defaultAddress, ShippingAddress::version).containsExactly(false, 1L));
        assertThat(shippingAddressRepositoryPort.findByIdAndMemberId(11L, MEMBER_ID))
            .hasValueSatisfying(address -> assertThat(address).extracting(ShippingAddress::defaultAddress, ShippingAddress::version).containsExactly(true, 1L));
        assertThat(shippingAddressRepositoryPort.findByIdAndMemberId(20L, OTHER_MEMBER_ID))
            .hasValueSatisfying(address -> assertThat(address.defaultAddress()).isTrue());
    }

    @Test
    @DisplayName("다른 회원의 주소를 기본으로 지정하면 바뀐 행이 없어 false 다 — 404 판정은 Processor 가 한다")
    void markDefaultRequiresOwner() {
        shippingAddressRepositoryPort.save(ShippingAddress.create(10L, MEMBER_ID, DOMESTIC, false));
        entityManager.clear();

        assertThat(shippingAddressRepositoryPort.markDefault(10L, OTHER_MEMBER_ID)).isFalse();
        entityManager.clear();
        assertThat(shippingAddressRepositoryPort.findByIdAndMemberId(10L, MEMBER_ID))
            .hasValueSatisfying(address -> assertThat(address.defaultAddress()).isFalse());
    }

    @Test
    @DisplayName("기존 기본을 풀지 않고 지정하면 유니크 키(uk_shipping_address_default_member_id) 위반이 AUTH_022 가 된다")
    void markDefaultWithoutClearingConflicts() {
        shippingAddressRepositoryPort.save(ShippingAddress.create(10L, MEMBER_ID, DOMESTIC, true));
        shippingAddressRepositoryPort.save(ShippingAddress.create(11L, MEMBER_ID, DOMESTIC, false));
        entityManager.clear();

        assertThatThrownBy(() -> shippingAddressRepositoryPort.markDefault(11L, MEMBER_ID))
            .isInstanceOf(ShippingAddressException.class)
            .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
            .isEqualTo(ShippingAddressErrorCode.SHIPPING_ADDRESS_UPDATE_CONFLICT);
    }

    @Test
    @DisplayName("기본을 둘째로 넣는 저장도 유니크 키 위반이 AUTH_022 가 된다")
    void saveSecondDefaultConflicts() {
        shippingAddressRepositoryPort.save(ShippingAddress.create(10L, MEMBER_ID, DOMESTIC, true));
        entityManager.clear();

        assertThatThrownBy(() -> shippingAddressRepositoryPort.save(ShippingAddress.create(11L, MEMBER_ID, DOMESTIC, true)))
            .isInstanceOf(ShippingAddressException.class)
            .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
            .isEqualTo(ShippingAddressErrorCode.SHIPPING_ADDRESS_UPDATE_CONFLICT);
    }

    @Test
    @DisplayName("유니크 키는 애플리케이션을 거치지 않은 INSERT 두 번도 거절한다 — 기본이 아닌(null) 행은 여럿 들어간다")
    void uniqueKeyRejectsDirectInserts() {
        entityManager.persist(entity(10L, MEMBER_ID, null, "KR"));
        entityManager.persist(entity(11L, MEMBER_ID, null, "KR"));
        entityManager.persist(entity(12L, MEMBER_ID, MEMBER_ID, "KR"));
        entityManager.flush();

        entityManager.persist(entity(13L, MEMBER_ID, MEMBER_ID, "KR"));
        assertThatThrownBy(() -> entityManager.flush()).rootCause().message().contains(ShippingAddressEntity.UK_DEFAULT_MEMBER_ID);
    }

    @Test
    @DisplayName("default_member_id 에 다른 회원 아이디가 들어가면 DB 가 거절한다 (ck_shipping_address_default_member_id)")
    void defaultMemberIdMustMatchOwner() {
        entityManager.persist(entity(10L, MEMBER_ID, OTHER_MEMBER_ID, "KR"));

        assertThatThrownBy(() -> entityManager.flush()).rootCause().message().contains("ck_shipping_address_default_member_id");
    }

    @Test
    @DisplayName("소문자 국가 코드는 DB 가 거절한다 (ck_shipping_address_country_code)")
    void countryCodeMustBeUppercase() {
        entityManager.persist(entity(10L, MEMBER_ID, null, "kr"));

        assertThatThrownBy(() -> entityManager.flush()).rootCause().message().contains("ck_shipping_address_country_code");
    }

    /**
     * Hibernate 는 예외를 바꾸기 전에 드라이버 메시지를 WARN 으로 남기고, pgjdbc 기본값은 그 메시지에 서버 DETAIL({@code Failing row contains (...)})을
     * 붙인다. persistence-core 자동 구성({@code ServerErrorDetailAutoConfiguration} — {@code @DataJpaTest} 슬라이스 목록에도 등록돼 있다)이 넣는
     * {@code logServerErrorDetail=false} 가 이 슬라이스의 커넥션 풀을 거쳐 드라이버까지 가는지 본다 (#173 · #279, {@code coding-conventions.md} §8). 로그는 그대로 남아 제약 이름은 보여야 한다 — 로그를 끈 것이 아니다.
     * 픽스처 값은 ASCII 다. 콘솔 인코딩이 달라 한글이 깨지면 "없다" 단언이 값이 새도 통과한다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("check 제약 위반(ck_shipping_address_country_code)의 로그 · 예외 메시지에 행 값(받는 사람 · 연락처)이 없고 제약 이름은 남는다")
    void constraintViolationLogOmitsRowValues(CapturedOutput output) {
        String probeName = "LogLeakProbe Recipient";
        String probePhone = "01099998888";
        ShippingAddressEntity lowercaseCountry = ShippingAddressEntity.builder()
            .id(10L).memberId(MEMBER_ID).recipientName(probeName).recipientPhone(probePhone).countryCode("kr").postalCode("06236")
            .addressLine1("1 LogLeakProbe St")
            .build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(lowercaseCountry))
            .rootCause().message()
            .contains("ck_shipping_address_country_code")
            .doesNotContain("Failing row contains").doesNotContain(probeName).doesNotContain(probePhone);
        assertThat(output.getAll())
            .contains("ck_shipping_address_country_code")
            .doesNotContain("Failing row contains").doesNotContain(probeName).doesNotContain(probePhone).doesNotContain("LogLeakProbe St");
    }

    @Test
    @DisplayName("옛 버전으로 저장하면 AUTH_022 다 — 기본 교체가 올린 버전도 전체 교체를 막는다")
    void staleVersionConflicts() {
        shippingAddressRepositoryPort.save(ShippingAddress.create(10L, MEMBER_ID, DOMESTIC, true));
        entityManager.clear();
        ShippingAddress read = shippingAddressRepositoryPort.findByIdAndMemberId(10L, MEMBER_ID).orElseThrow();
        shippingAddressRepositoryPort.clearDefault(MEMBER_ID);
        entityManager.clear();

        assertThatThrownBy(() -> shippingAddressRepositoryPort.save(read.replaceContent(INTERNATIONAL)))
            .isInstanceOf(ShippingAddressException.class)
            .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
            .isEqualTo(ShippingAddressErrorCode.SHIPPING_ADDRESS_UPDATE_CONFLICT);
    }

    @Test
    @DisplayName("전체 교체는 내용만 바꾸고 버전을 올린다 — 기본 여부 · 생성 시각은 그대로")
    void replaceContent() {
        shippingAddressRepositoryPort.save(ShippingAddress.create(10L, MEMBER_ID, DOMESTIC, true));
        entityManager.clear();
        ShippingAddress read = shippingAddressRepositoryPort.findByIdAndMemberId(10L, MEMBER_ID).orElseThrow();

        ShippingAddress saved = shippingAddressRepositoryPort.save(read.replaceContent(INTERNATIONAL));
        entityManager.clear();

        assertThat(saved).isEqualTo(new ShippingAddress(10L, MEMBER_ID, INTERNATIONAL, true, 1L));
        assertThat(entityManager.find(ShippingAddressEntity.class, 10L).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("삭제는 그 회원의 주소만 지운다")
    void delete() {
        shippingAddressRepositoryPort.save(ShippingAddress.create(10L, MEMBER_ID, DOMESTIC, true));
        entityManager.clear();

        assertThat(shippingAddressRepositoryPort.deleteByIdAndMemberId(10L, MEMBER_ID)).isTrue();
        assertThat(shippingAddressRepositoryPort.deleteByIdAndMemberId(10L, MEMBER_ID)).isFalse();
        assertThat(shippingAddressRepositoryPort.countByMemberId(MEMBER_ID)).isZero();
    }

    @Test
    @DisplayName("지운 행을 옛 버전으로 저장하면 되살리지 않고 AUTH_022 다 — 전체 교체가 삭제와 겹친 경우")
    void savingDeletedRowConflicts() {
        shippingAddressRepositoryPort.save(ShippingAddress.create(10L, MEMBER_ID, DOMESTIC, false));
        entityManager.clear();
        ShippingAddress read = shippingAddressRepositoryPort.findByIdAndMemberId(10L, MEMBER_ID).orElseThrow();
        shippingAddressRepositoryPort.deleteByIdAndMemberId(10L, MEMBER_ID);
        entityManager.clear();

        assertThatThrownBy(() -> shippingAddressRepositoryPort.save(read.replaceContent(INTERNATIONAL)))
            .isInstanceOf(ShippingAddressException.class)
            .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
            .isEqualTo(ShippingAddressErrorCode.SHIPPING_ADDRESS_UPDATE_CONFLICT);
        entityManager.clear();
        assertThat(entityManager.find(ShippingAddressEntity.class, 10L)).isNull();
    }

    @Test
    @DisplayName("기본을 지우고 승격하면 남은 주소 중 아이디가 가장 큰 행이 기본이다 (삭제 → 최근 조회 → 지정)")
    void promoteLatestAfterDeletingDefault() {
        shippingAddressRepositoryPort.save(ShippingAddress.create(10L, MEMBER_ID, DOMESTIC, true));
        shippingAddressRepositoryPort.save(ShippingAddress.create(12L, MEMBER_ID, DOMESTIC, false));
        shippingAddressRepositoryPort.save(ShippingAddress.create(11L, MEMBER_ID, DOMESTIC, false));
        shippingAddressRepositoryPort.save(ShippingAddress.create(30L, OTHER_MEMBER_ID, DOMESTIC, true));
        entityManager.clear();

        assertThat(shippingAddressRepositoryPort.deleteByIdAndMemberId(10L, MEMBER_ID)).isTrue();
        ShippingAddress latest = shippingAddressRepositoryPort.findLatestByMemberId(MEMBER_ID).orElseThrow();
        assertThat(shippingAddressRepositoryPort.markDefault(latest.id(), MEMBER_ID)).isTrue();
        entityManager.clear();

        assertThat(shippingAddressRepositoryPort.findAllByMemberId(MEMBER_ID))
            .extracting(ShippingAddress::id, ShippingAddress::defaultAddress)
            .containsExactly(tuple(12L, true), tuple(11L, false));
        assertThat(shippingAddressRepositoryPort.markDefault(10L, MEMBER_ID)).as("지운 행은 바뀌지 않는다").isFalse();
    }

    private static ShippingAddressEntity entity(long id, long memberId, Long defaultMemberId, String countryCode) {
        return ShippingAddressEntity.builder()
            .id(id).memberId(memberId).recipientName("홍길동").recipientPhone("01012345678").countryCode(countryCode).postalCode("06236")
            .addressLine1("서울특별시 강남구 테헤란로 123").defaultMemberId(defaultMemberId)
            .build();
    }
}
