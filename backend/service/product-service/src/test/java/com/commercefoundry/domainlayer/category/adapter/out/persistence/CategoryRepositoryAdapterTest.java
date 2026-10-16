package com.commercefoundry.domainlayer.category.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.commercefoundry.domainlayer.category.adapter.out.persistence.entity.CategoryEntity;
import com.commercefoundry.domainlayer.category.application.mapper.CategoryMapperImpl;
import com.commercefoundry.domainlayer.category.application.port.out.CategoryRepositoryPort;
import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import java.util.List;
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
 * PostgreSQL 컨테이너에 DDL 스냅숏을 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한 뒤 실제로 질의한다
 * ({@code bootstrap-conventions.md} "리포지토리 테스트"). Docker 가 없으면 건너뛴다 — CI 에서 돈다.
 */
@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=product",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, CategoryRepositoryAdapter.class, CategoryMapperImpl.class})
class CategoryRepositoryAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private CategoryRepositoryPort categoryRepositoryPort;

    @Test
    @DisplayName("숨김을 포함한 전체 카테고리를 도메인으로 읽는다")
    void findAll() {
        entityManager.persist(category(1L, null, "상의", 0, true));
        entityManager.persist(category(11L, 1L, "셔츠", 1, false));
        entityManager.flush();
        entityManager.clear();

        List<Category> categories = categoryRepositoryPort.findAll();

        assertThat(categories)
            .extracting(Category::id, Category::parentId, Category::name, Category::displayOrder, Category::visible)
            .containsExactlyInAnyOrder(tuple(1L, null, "상의", 0, true), tuple(11L, 1L, "셔츠", 1, false));
    }

    @Test
    @DisplayName("감사 시각(created_at · updated_at)이 채워진다 — JPA Auditing 이 켜져 있다")
    void auditing() {
        entityManager.persistAndFlush(category(1L, null, "상의", 0, true));
        entityManager.clear();

        CategoryEntity saved = entityManager.find(CategoryEntity.class, 1L);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    private static CategoryEntity category(Long id, Long parentId, String name, int displayOrder, boolean visible) {
        return CategoryEntity.builder().id(id).parentId(parentId).name(name).displayOrder(displayOrder).visible(visible).build();
    }
}
