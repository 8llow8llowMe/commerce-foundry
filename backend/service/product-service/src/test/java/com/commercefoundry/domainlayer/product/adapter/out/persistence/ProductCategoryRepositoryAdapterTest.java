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
import java.time.Instant;
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
 * 어드민 카테고리 삭제의 "연결 상품" 판정 — QueryDSL 질의(진열 → product 조인, 대표)를 실제 PostgreSQL 에 보낸다 ({@code coding-conventions.md} §9-5).
 * 삭제(soft delete) 상품은 세지 않는다. Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다 ({@link EnabledWithDockerOrOnCi}).
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
    //   103   30           30, 31, 20        삭제(soft delete) — 세지 않는다
    @BeforeEach
    void setUp() {
        entityManager.persist(product(101L, 10L, true, SaleStatus.ON_SALE));
        entityManager.persist(product(102L, 20L, false, SaleStatus.STOP_SELLING));
        entityManager.persist(deletedProduct(103L, 30L));
        entityManager.persist(displayedIn(9001L, 101L, 10L));
        entityManager.persist(displayedIn(9002L, 101L, 11L));
        entityManager.persist(displayedIn(9003L, 103L, 30L));
        entityManager.persist(displayedIn(9004L, 103L, 31L));
        entityManager.persist(displayedIn(9005L, 103L, 20L));
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
    @DisplayName("대표 카테고리로만 쓰여도(진열에 빠진 데이터 오류) true 다 — 숨김 · 판매 종료 상품도 세고, 진열 행이 삭제 상품뿐이면 대표 카테고리 질의로 넘어간다")
    void representativeOnly() {
        assertThat(productCategoryRepositoryPort.existsProductInCategory(20L)).isTrue();
    }

    @Test
    @DisplayName("삭제 상품만 대표 · 진열 카테고리로 쓰면 false 다 — 지울 수 있다")
    void deletedProductOnly() {
        assertThat(productCategoryRepositoryPort.existsProductInCategory(30L)).as("대표 + 진열").isFalse();
        assertThat(productCategoryRepositoryPort.existsProductInCategory(31L)).as("진열만").isFalse();
    }

    @Test
    @DisplayName("어느 상품도 쓰지 않으면 false 다")
    void unused() {
        assertThat(productCategoryRepositoryPort.existsProductInCategory(40L)).isFalse();
    }

    /** 삭제(soft delete) 상품 — check({@code ck_product_deleted_hidden})가 허용하는 숨김 · 판매 종료다. */
    private static ProductEntity deletedProduct(Long id, Long categoryId) {
        return ProductEntity.builder()
            .id(id)
            .categoryId(categoryId)
            .name("상품 " + id)
            .saleStatus(SaleStatus.STOP_SELLING)
            .visible(false)
            .salePriceAmount(10_000L)
            .salePriceCurrency(KRW)
            .deletedAt(Instant.parse("2026-10-20T00:00:00Z"))
            .build();
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
