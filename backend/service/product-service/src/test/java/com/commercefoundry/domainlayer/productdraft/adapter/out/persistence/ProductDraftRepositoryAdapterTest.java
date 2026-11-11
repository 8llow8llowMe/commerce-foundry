package com.commercefoundry.domainlayer.productdraft.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.entity.ProductDraftEntity;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftRepositoryPort;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageSource;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageType;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL 컨테이너에 DDL 스냅숏을 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한 뒤 실제로 저장 · 조회한다 — 입력 JSON 왕복(한글 · 개행 · 순서) ·
 * 거절 갱신 · 버전 · 거절 칸 check 제약 ({@code bootstrap-conventions.md} "리포지토리 테스트"). Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다.
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
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, ProductDraftRepositoryAdapter.class})
class ProductDraftRepositoryAdapterTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-11-08T03:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ProductDraftRepositoryPort port;

    private static ProductDraft draft(long id) {
        ProductDraftFacts facts = new ProductDraftFacts("린넨 \"저고리\"", 12L, List.of("린넨 100%", "면 안감"), List.of("찬물 손세탁"), "총장 62cm\n가슴단면 54cm",
            "대한민국", List.of(), "차분하게", List.of("여름"), List.of("최고"));
        List<ProductDraftImage> images = List.of(
            new ProductDraftImage("https://cdn.example.com/media/b.jpg", ProductDraftImageType.GALLERY, null, null,
                new ProductDraftImageSource("products/media/7/0f8fad5b-d9cb-469f-a165-70867728950e/" + "b".repeat(64) + ".jpg", "\"etag-b\"", 5_242_880L, "image/jpeg")),
            new ProductDraftImage("https://cdn.example.com/media/a.jpg", ProductDraftImageType.THUMBNAIL, 1200, 1600)
        );
        return ProductDraft.request(id, 100L, facts, images, EXPIRES_AT);
    }

    @Test
    @DisplayName("저장한 입력이 그대로 돌아온다 — 한글 · 따옴표 · 개행 · 이미지 순서 · 생성 시각")
    void roundTrip() {
        ProductDraft saved = port.insert(draft(1L));
        entityManager.flush();
        entityManager.clear();

        ProductDraft found = port.findById(1L).orElseThrow();

        assertThat(saved.createdAt()).isNotNull();
        assertThat(found.status()).isEqualTo(ProductDraftStatus.REQUESTED);
        assertThat(found.requestedByMemberId()).isEqualTo(100L);
        assertThat(found.facts()).isEqualTo(draft(1L).facts());
        assertThat(found.images()).isEqualTo(draft(1L).images());
        assertThat(found.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(found.createdAt()).as("생성 응답과 조회 응답의 시각이 같다(마이크로초)").isEqualTo(saved.createdAt());
        assertThat(port.findById(2L)).isEmpty();
        assertThat(found.images().get(0).source()).as("고정한 키 · ETag · 크기 · 형식도 돌아온다").isEqualTo(draft(1L).images().get(0).source());
        assertThat(found.images().get(1).source()).isNull();
    }

    @Test
    @DisplayName("원본 칸이 없는 PR-1 모양 입력 JSON 도 읽는다 — 이미지 원본은 null 이다(생성 internal 이 043 으로 거절)")
    void readsLegacyInputWithoutSource() {
        entityManager.persist(entityBuilder().inputJson(
            "{\"facts\":{\"nameHint\":\"저고리\"},\"images\":[{\"url\":\"https://cdn.example.com/media/a.jpg\",\"imageType\":\"THUMBNAIL\",\"width\":null,\"height\":null}]}"
        ).build());
        entityManager.flush();
        entityManager.clear();

        ProductDraft found = port.findById(1L).orElseThrow();

        assertThat(found.images()).singleElement().satisfies(image -> {
            assertThat(image.url()).isEqualTo("https://cdn.example.com/media/a.jpg");
            assertThat(image.source()).isNull();
        });
    }

    @Test
    @DisplayName("읽은 뒤 다른 트랜잭션이 행을 바꿨으면 거절 갱신이 그 호출 안에서 낙관적 락 예외를 낸다 — Facade 가 409 로 바꾼다")
    void optimisticLockInsideUpdate() {
        ProductDraft saved = port.insert(draft(1L));
        entityManager.flush();
        entityManager.clear();
        port.findById(1L).orElseThrow();
        entityManager.getEntityManager().createNativeQuery("update product.product_draft set version = version + 1 where id = 1").executeUpdate();

        assertThatThrownBy(() -> port.updateDecision(saved.reject(200L, Instant.parse("2026-11-02T03:00:00Z"))))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("거절 갱신은 상태 · 거절 칸을 바꾸고 버전을 올린다")
    void updateDecision() {
        ProductDraft saved = port.insert(draft(1L));
        entityManager.flush();
        Instant rejectedAt = Instant.parse("2026-11-02T03:00:00Z");

        port.updateDecision(saved.reject(200L, rejectedAt));
        entityManager.clear();

        ProductDraftEntity entity = entityManager.find(ProductDraftEntity.class, 1L);
        assertThat(entity.getStatus()).isEqualTo(ProductDraftStatus.REJECTED);
        assertThat(entity.getRejectedByMemberId()).isEqualTo(200L);
        assertThat(entity.getRejectedAt()).isEqualTo(rejectedAt);
        assertThat(entity.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("거절 상태인데 거절 칸이 없으면 ck_product_draft_rejected 에 걸린다")
    void rejectedWithoutDecisionCheck() {
        entityManager.persist(entityBuilder().status(ProductDraftStatus.REJECTED).build());
        assertThatThrownBy(() -> entityManager.flush()).rootCause().hasMessageContaining("ck_product_draft_rejected");
    }

    @Test
    @DisplayName("거절 칸이 있는데 거절 상태가 아니면 ck_product_draft_rejected 에 걸린다")
    void decisionWithoutRejectedCheck() {
        entityManager.persist(entityBuilder().rejectedByMemberId(1L).rejectedAt(EXPIRES_AT).build());
        assertThatThrownBy(() -> entityManager.flush()).rootCause().hasMessageContaining("ck_product_draft_rejected");
    }

    private static ProductDraftEntity.ProductDraftEntityBuilder entityBuilder() {
        return ProductDraftEntity.builder().id(1L).status(ProductDraftStatus.REQUESTED).requestedByMemberId(100L).inputJson("{}").expiresAt(EXPIRES_AT);
    }
}
