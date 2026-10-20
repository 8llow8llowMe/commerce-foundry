package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductCategoryEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCategoryRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
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
 * 어드민 카테고리 삭제의 "연결 상품" 판정 — 파생 exists 질의를 실제 PostgreSQL 에 보낸다 ({@code coding-conventions.md} §9-5).
 * Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다 ({@link EnabledWithDockerOrOnCi}).
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=product",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, ProductCategoryRepositoryAdapter.class})
class ProductCategoryRepositoryAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ProductCategoryRepositoryPort productCategoryRepositoryPort;

    //   상품  대표 카테고리  진열 카테고리      비고
    //   101   10           10, 11            정상
    //   102   20           (없음)            숨김 · 판매 종료 — 대표가 진열에 없는 이관 데이터 오류
    @BeforeEach
    void setUp() {
        entityManager.persist(product(101L, 10L, true, SaleStatus.ON_SALE));
        entityManager.persist(product(102L, 20L, false, SaleStatus.STOP_SELLING));
        entityManager.persist(displayedIn(9001L, 101L, 10L));
        entityManager.persist(displayedIn(9002L, 101L, 11L));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("대표 · 진열 카테고리로 쓰이면 true 다")
    void displayedOrRepresentative() {
        assertThat(productCategoryRepositoryPort.existsProductInCategory(10L)).isTrue();
        assertThat(productCategoryRepositoryPort.existsProductInCategory(11L)).isTrue();
    }

    @Test
    @DisplayName("대표 카테고리로만 쓰여도(진열에 빠진 데이터 오류) true 다 — 숨김 · 판매 종료 상품도 센다")
    void representativeOnly() {
        assertThat(productCategoryRepositoryPort.existsProductInCategory(20L)).isTrue();
    }

    @Test
    @DisplayName("어느 상품도 쓰지 않으면 false 다")
    void unused() {
        assertThat(productCategoryRepositoryPort.existsProductInCategory(30L)).isFalse();
    }

    private static ProductEntity product(Long id, Long categoryId, boolean visible, SaleStatus saleStatus) {
        return ProductEntity.builder()
            .id(id)
            .categoryId(categoryId)
            .name("상품 " + id)
            .saleStatus(saleStatus)
            .visible(visible)
            .salePriceAmount(10_000L)
            .salePriceCurrency(KRW)
            .build();
    }

    private static ProductCategoryEntity displayedIn(Long id, Long productId, Long categoryId) {
        return ProductCategoryEntity.builder().id(id).productId(productId).categoryId(categoryId).displayOrder(0).build();
    }
}
