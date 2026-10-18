package com.commercefoundry.domainlayer.cart.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.cart.adapter.out.persistence.entity.CartItemCustomOptionEntity;
import com.commercefoundry.domainlayer.cart.adapter.out.persistence.entity.CartItemEntity;
import com.commercefoundry.domainlayer.cart.application.exception.CartErrorCode;
import com.commercefoundry.domainlayer.cart.application.exception.CartException;
import com.commercefoundry.domainlayer.cart.application.mapper.CartItemMapperImpl;
import com.commercefoundry.domainlayer.cart.application.port.out.CartItemRepositoryPort;
import com.commercefoundry.domainlayer.cart.domain.model.CartItem;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOption;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOptions;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 장바구니 영속성 어댑터를 실제 PostgreSQL 에 질의해 본다 — 파생 쿼리 · JPQL 은 컴파일로 검증되지 않는다 ({@code coding-conventions.md} §9-5).
 * 유니크 키(같은 줄 동시 담기)와 {@code @Version}(동시 수정)이 실제로 막히는지도 본다.
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
    "spring.jpa.properties.hibernate.default_schema=orders",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, OrderServiceFeaturesConfig.class, CartItemRepositoryAdapter.class, CartItemMapperImpl.class})
class CartItemPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");

    private static final long MEMBER = 1L;
    private static final long OTHER_MEMBER = 2L;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private CartItemRepositoryPort cartItemRepositoryPort;

    private final AtomicLong ids = new AtomicLong(5000);

    // 회원 1: 101(SKU 9001, 입력 2칸) · 102(SKU 9002, 입력 없음) · 103(SKU 9001, 다른 입력)   회원 2: 201(SKU 9001)
    @BeforeEach
    void setUp() {
        entityManager.persist(item(101L, MEMBER, 9001L, 2, CustomOptions.of(List.of(CustomOption.of("waist", "80"), CustomOption.of("chest", "96")))));
        entityManager.persist(option(1011L, 101L, "waist", "80", 0));
        entityManager.persist(option(1012L, 101L, "chest", "96", 1));
        entityManager.persist(item(102L, MEMBER, 9002L, 1, CustomOptions.empty()));
        entityManager.persist(item(103L, MEMBER, 9001L, 1, CustomOptions.of(List.of(CustomOption.of("chest", "100")))));
        entityManager.persist(option(1031L, 103L, "chest", "100", 0));
        entityManager.persist(item(201L, OTHER_MEMBER, 9001L, 1, CustomOptions.empty()));
        entityManager.flush();
        entityManager.clear();
    }

    private static CartItemEntity item(long id, long memberId, long skuId, int quantity, CustomOptions customOptions) {
        return CartItemEntity.builder().id(id).memberId(memberId).skuId(skuId).quantity(quantity).customOptionHash(customOptions.hash()).build();
    }

    private static CartItemCustomOptionEntity option(long id, long cartItemId, String fieldKey, String value, int displayOrder) {
        return CartItemCustomOptionEntity.builder().id(id).cartItemId(cartItemId).fieldKey(fieldKey).fieldValue(value).displayOrder(displayOrder).build();
    }

    @Test
    @DisplayName("회원의 줄을 최근 순(아이디 내림차순)으로, 입력은 표시 순서로 채워 읽는다 — 다른 회원의 줄은 없다")
    void findAllByMemberId() {
        List<CartItem> items = cartItemRepositoryPort.findAllByMemberId(MEMBER);

        assertThat(items).extracting(CartItem::id).containsExactly(103L, 102L, 101L);
        CartItem withOptions = items.get(2);
        assertThat(withOptions.customOptions().values()).extracting(CustomOption::fieldKey).containsExactly("waist", "chest");
        assertThat(withOptions.customOptions().values()).extracting(CustomOption::id).containsExactly(1011L, 1012L);
        assertThat(withOptions.version()).isZero();
        assertThat(items.get(1).customOptions().isEmpty()).isTrue();
        assertThat(cartItemRepositoryPort.findAllByMemberId(99L)).isEmpty();
    }

    @Test
    @DisplayName("줄 하나는 회원이 맞아야 읽힌다")
    void findByIdAndMemberId() {
        assertThat(cartItemRepositoryPort.findByIdAndMemberId(101L, MEMBER)).get()
            .satisfies(item -> assertThat(item.customOptions().values()).hasSize(2));
        assertThat(cartItemRepositoryPort.findByIdAndMemberId(201L, MEMBER)).isEmpty();
        assertThat(cartItemRepositoryPort.findByIdAndMemberId(999L, MEMBER)).isEmpty();
    }

    @Test
    @DisplayName("새 줄은 입력과 함께 넣고 버전 0 으로 시작한다")
    void insertsNewLine() {
        CustomOptions options = CustomOptions.of(List.of(CustomOption.of("memo", "소매 길게"), CustomOption.of("chest", "90"))).withIds(ids::incrementAndGet);

        cartItemRepositoryPort.saveAll(List.of(CartItem.create(301L, MEMBER, 9003L, 3, options, 99)));
        entityManager.clear();

        CartItem saved = cartItemRepositoryPort.findByIdAndMemberId(301L, MEMBER).orElseThrow();
        assertThat(saved.quantity()).isEqualTo(3);
        assertThat(saved.version()).isZero();
        assertThat(saved.customOptions().values()).extracting(CustomOption::fieldKey).containsExactly("memo", "chest");
        assertThat(saved.customOptions().hash()).isEqualTo(options.hash());
        assertThat(entityManager.find(CartItemEntity.class, 301L).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("저장된 줄은 수량만 고치고 버전을 올린다 — 생성 시각은 그대로다")
    void updatesQuantity() {
        CartItem item = cartItemRepositoryPort.findByIdAndMemberId(101L, MEMBER).orElseThrow();
        Instant createdAt = entityManager.find(CartItemEntity.class, 101L).getCreatedAt();
        entityManager.clear();

        cartItemRepositoryPort.saveAll(List.of(item.changeQuantity(7, 99)));
        entityManager.clear();

        CartItemEntity updated = entityManager.find(CartItemEntity.class, 101L);
        assertThat(updated.getQuantity()).isEqualTo(7);
        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(cartItemRepositoryPort.findByIdAndMemberId(101L, MEMBER).orElseThrow().customOptions().values()).hasSize(2);
    }

    @Test
    @DisplayName("다른 요청이 먼저 고친 줄(낡은 버전)을 저장하면 CART_CONCURRENTLY_MODIFIED 다")
    void staleVersionIsRejected() {
        CartItem stale = cartItemRepositoryPort.findByIdAndMemberId(102L, MEMBER).orElseThrow();
        cartItemRepositoryPort.saveAll(List.of(stale.changeQuantity(2, 99)));
        entityManager.clear();

        assertThatThrownBy(() -> cartItemRepositoryPort.saveAll(List.of(stale.changeQuantity(5, 99))))
            .isInstanceOfSatisfying(CartException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(CartErrorCode.CART_CONCURRENTLY_MODIFIED));
    }

    @Test
    @DisplayName("같은 줄(회원 · SKU · 입력 해시)을 또 만들면 유니크 키가 막고 CART_CONCURRENTLY_MODIFIED 다")
    void duplicateLineIsRejected() {
        CartItem duplicate = CartItem.create(302L, MEMBER, 9002L, 1, CustomOptions.empty(), 99);

        assertThatThrownBy(() -> cartItemRepositoryPort.saveAll(List.of(duplicate)))
            .isInstanceOfSatisfying(CartException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(CartErrorCode.CART_CONCURRENTLY_MODIFIED));
    }

    @Test
    @DisplayName("다른 회원은 같은 SKU · 같은 입력으로 줄을 만들 수 있다")
    void sameLineForOtherMember() {
        cartItemRepositoryPort.saveAll(List.of(CartItem.create(303L, OTHER_MEMBER, 9002L, 1, CustomOptions.empty(), 99)));
        entityManager.clear();

        assertThat(cartItemRepositoryPort.findAllByMemberId(OTHER_MEMBER)).extracting(CartItem::id).containsExactly(303L, 201L);
    }

    @Test
    @DisplayName("줄을 지우면 입력도 지운다 — 다른 줄의 입력은 남는다")
    void deletes() {
        cartItemRepositoryPort.delete(101L);
        entityManager.clear();

        assertThat(cartItemRepositoryPort.findAllByMemberId(MEMBER)).extracting(CartItem::id).containsExactly(103L, 102L);
        assertThat(entityManager.find(CartItemCustomOptionEntity.class, 1011L)).isNull();
        assertThat(entityManager.find(CartItemCustomOptionEntity.class, 1031L)).isNotNull();
    }

    @Test
    @DisplayName("수량 0 은 check 제약이 막는다")
    void quantityCheckConstraint() {
        assertThatThrownBy(() -> {
            entityManager.persist(item(304L, MEMBER, 9004L, 0, CustomOptions.empty()));
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }
}
