package com.commercefoundry.domainlayer.category.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.commercefoundry.domainlayer.category.adapter.out.persistence.entity.CategoryEntity;
import com.commercefoundry.domainlayer.category.application.mapper.CategoryMapperImpl;
import com.commercefoundry.domainlayer.category.application.port.out.CategoryRepositoryPort;
import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
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
 * ({@code bootstrap-conventions.md} "리포지토리 테스트"). Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다.
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

    @Test
    @DisplayName("findAllForUpdate 는 잠근 뒤 숨김 포함 전체를 읽는다")
    void findAllForUpdate() {
        entityManager.persist(category(1L, null, "상의", 0, true));
        entityManager.persist(category(11L, 1L, "셔츠", 1, false));
        entityManager.flush();
        entityManager.clear();

        assertThat(categoryRepositoryPort.findAllForUpdate()).extracting(Category::id).containsExactlyInAnyOrder(1L, 11L);
    }

    @Test
    @DisplayName("insert 는 merge 가 아니라 persist 다 — 감사 시각이 채워지고, 같은 아이디를 다시 넣으면 덮어쓰지 않고 PK 위반이다")
    void insertPersists() {
        categoryRepositoryPort.insert(new Category(1L, null, "상의", 0, true));
        entityManager.flush();
        entityManager.clear();

        CategoryEntity saved = entityManager.find(CategoryEntity.class, 1L);
        assertThat(saved.getName()).isEqualTo("상의");
        assertThat(saved.getCreatedAt()).isNotNull();
        entityManager.clear();

        assertThatThrownBy(() -> {
            categoryRepositoryPort.insert(new Category(1L, null, "덮어쓰기", 1, false));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("update 는 관리 엔티티를 고쳐 상위 이동 · 이름 · 순서 · 노출을 바꾼다 — 생성 시각은 그대로다")
    void update() {
        entityManager.persist(category(1L, null, "상의", 0, true));
        entityManager.persist(category(2L, null, "하의", 1, true));
        entityManager.persist(category(11L, 1L, "셔츠", 1, true));
        entityManager.flush();
        entityManager.clear();
        Instant createdAt = entityManager.find(CategoryEntity.class, 11L).getCreatedAt();
        entityManager.clear();

        categoryRepositoryPort.update(new Category(11L, 2L, "바지", 0, false));
        entityManager.flush();
        entityManager.clear();

        CategoryEntity updated = entityManager.find(CategoryEntity.class, 11L);
        assertThat(updated)
            .extracting(CategoryEntity::getParentId, CategoryEntity::getName, CategoryEntity::getDisplayOrder, CategoryEntity::isVisible)
            .containsExactly(2L, "바지", 0, false);
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("update 는 없는 아이디면 IllegalStateException 이다 — 새 행을 만들지 않는다")
    void updateUnknown() {
        assertThatThrownBy(() -> categoryRepositoryPort.update(new Category(999L, null, "없음", 0, true))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("deleteById 는 그 행만 지운다")
    void deleteById() {
        entityManager.persist(category(1L, null, "상의", 0, true));
        entityManager.persist(category(2L, null, "하의", 1, true));
        entityManager.flush();
        entityManager.clear();

        categoryRepositoryPort.deleteById(2L);
        entityManager.flush();
        entityManager.clear();

        assertThat(categoryRepositoryPort.findAll()).extracting(Category::id).containsExactly(1L);
    }

    private static CategoryEntity category(Long id, Long parentId, String name, int displayOrder, boolean visible) {
        return CategoryEntity.builder().id(id).parentId(parentId).name(name).displayOrder(displayOrder).visible(visible).build();
    }
}
