package com.commercefoundry.domainlayer.look.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.look.adapter.out.persistence.entity.LookEntity;
import com.commercefoundry.domainlayer.look.adapter.out.persistence.entity.LookItemEntity;
import com.commercefoundry.domainlayer.look.application.mapper.LookMapperImpl;
import com.commercefoundry.domainlayer.look.application.port.out.LookRepositoryPort;
import com.commercefoundry.domainlayer.look.domain.model.Look;
import com.commercefoundry.domainlayer.look.domain.model.LookContent;
import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import com.commercefoundry.domainlayer.look.domain.model.LookItem;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Slice;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL 컨테이너에 DDL 스냅숏을 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한 뒤 실제로 질의한다 — 정렬 · in 절 한 번 · 아이템 교체 · 유니크 키 · check 제약 ·
 * 고객용 보이는 룩(한 쪽 · 상품이 든 룩 조인) ({@code bootstrap-conventions.md} "리포지토리 테스트"). Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=product",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, LookRepositoryAdapter.class, LookMapperImpl.class})
class LookRepositoryAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private LookRepositoryPort port;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    @DisplayName("룩은 노출 순서 → 아이디, 아이템은 룩 → 순서 → 아이디로 읽고 이미지 칸이 domain 으로 돌아온다. 아이템은 in 절 한 쿼리다")
    void readsOrderedInOneQuery() {
        port.insert(content(look(3L, 1, true), item(31L, 3L, 700L, 0)));
        port.insert(content(look(2L, 0, true), item(22L, 2L, 801L, 1), item(21L, 2L, 800L, 0)));
        port.insert(content(look(1L, 1, false), item(11L, 1L, 700L, 0)));
        entityManager.flush();
        entityManager.clear();

        assertThat(port.findAll()).extracting(Look::id).containsExactly(2L, 1L, 3L);
        assertThat(port.findById(2L).orElseThrow().image()).isEqualTo(new LookImage("https://cdn.example.com/media/2.jpg", 1200, 1600, "대체2"));

        statistics.clear();
        List<LookItem> items = port.findItemsByLookIds(List.of(3L, 2L));
        assertThat(items).extracting(LookItem::id).containsExactly(21L, 22L, 31L);
        assertThat(statistics.getPrepareStatementCount()).as("룩 두 개의 아이템을 한 쿼리로").isEqualTo(1);
    }

    @Test
    @DisplayName("빈 컬렉션이면 쿼리 없이 빈 목록이다")
    void emptyIdsNoQuery() {
        statistics.clear();

        assertThat(port.findItemsByLookIds(List.of())).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    @DisplayName("교체는 룩 칸을 바꾸고 아이템을 지운 뒤 같은 순서 번호 · 같은 상품으로 다시 넣어도 유니크 키에 걸리지 않는다")
    void replaceWithoutUniqueConflict() {
        port.insert(content(look(1L, 0, true), item(11L, 1L, 700L, 0), item(12L, 1L, 701L, 1)));
        entityManager.flush();
        entityManager.clear();

        Look changed = new Look(1L, "바뀐 제목", "설명", new LookImage("https://cdn.example.com/media/new.jpg", null, null, null), false, 0);
        port.replace(content(changed, item(13L, 1L, 701L, 0), item(14L, 1L, 700L, 1)));
        entityManager.flush();
        entityManager.clear();

        Look reloaded = port.findById(1L).orElseThrow();
        assertThat(reloaded.title()).isEqualTo("바뀐 제목");
        assertThat(reloaded.visible()).isFalse();
        assertThat(reloaded.image().url()).isEqualTo("https://cdn.example.com/media/new.jpg");
        assertThat(port.findItemsByLookIds(List.of(1L))).extracting(LookItem::productId).containsExactly(701L, 700L);
    }

    @Test
    @DisplayName("상품이 든 룩은 보이는 룩만 노출 순서로 낸다 — 숨긴 룩 · 다른 상품의 룩은 빠진다")
    void visibleLooksByProduct() {
        port.insert(content(look(1L, 2, true), item(11L, 1L, 700L, 0)));
        port.insert(content(look(2L, 0, false), item(21L, 2L, 700L, 0)));
        port.insert(content(look(3L, 1, true), item(31L, 3L, 701L, 0), item(32L, 3L, 700L, 1)));
        port.insert(content(look(4L, 0, true), item(41L, 4L, 701L, 0)));
        entityManager.flush();
        entityManager.clear();

        assertThat(port.findVisibleByProductId(700L)).extracting(Look::id).containsExactly(3L, 1L);
        assertThat(port.findVisibleByProductId(700L).getFirst().image()).isEqualTo(new LookImage("https://cdn.example.com/media/3.jpg", 1200, 1600, "대체3"));
        assertThat(port.findVisibleByProductId(999L)).isEmpty();
    }

    @Test
    @DisplayName("고객 목록 한 쪽은 보이는 룩만 노출 순서 → 아이디(어드민 목록과 같은 순서)로 자르고 다음 쪽 여부를 안다")
    void visibleSlice() {
        port.insert(content(look(1L, 1, true), item(11L, 1L, 700L, 0)));
        port.insert(content(look(2L, 0, false), item(21L, 2L, 700L, 0)));
        port.insert(content(look(3L, 0, true), item(31L, 3L, 700L, 0)));
        port.insert(content(look(4L, 1, true), item(41L, 4L, 700L, 0)));
        entityManager.flush();
        entityManager.clear();

        Slice<Look> first = port.findVisibleSlice(0, 2);
        Slice<Look> second = port.findVisibleSlice(1, 2);

        assertThat(first.getContent()).extracting(Look::id).containsExactly(3L, 1L);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.getContent()).extracting(Look::id).containsExactly(4L);
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    @DisplayName("잠금 조회는 트랜잭션 안에서 전체 룩을 노출 순서로 돌려주고, 순서 변경 · 삭제가 그대로 다시 읽힌다")
    void lockReorderDelete() {
        port.insert(content(look(1L, 0, true), item(11L, 1L, 700L, 0)));
        port.insert(content(look(2L, 1, false), item(21L, 2L, 701L, 0)));
        entityManager.flush();
        entityManager.clear();

        assertThat(port.findAllForUpdate()).extracting(Look::id).containsExactly(1L, 2L);
        port.changeDisplayOrders(Map.of(1L, 1, 2L, 0));
        entityManager.flush();
        entityManager.clear();
        assertThat(port.findAll()).extracting(Look::id).containsExactly(2L, 1L);

        port.delete(1L);
        entityManager.flush();
        entityManager.clear();
        assertThat(port.findById(1L)).isEmpty();
        assertThat(port.findItemsByLookIds(List.of(1L))).isEmpty();
    }

    @Test
    @DisplayName("한 룩에 같은 상품이 두 번이면 uk_look_item_look_id_product_id 에 걸린다")
    void uniqueProduct() {
        entityManager.persist(lookEntity(1L));
        entityManager.persist(LookItemEntity.builder().id(11L).lookId(1L).productId(700L).displayOrder(0).build());
        entityManager.persist(LookItemEntity.builder().id(12L).lookId(1L).productId(700L).displayOrder(1).build());
        assertThatThrownBy(() -> entityManager.flush()).rootCause().hasMessageContaining("uk_look_item_look_id_product_id");
    }

    @Test
    @DisplayName("같은 룩의 같은 순서 번호는 uk_look_item_look_id_display_order 에 걸린다")
    void uniqueDisplayOrder() {
        entityManager.persist(lookEntity(1L));
        entityManager.persist(LookItemEntity.builder().id(11L).lookId(1L).productId(700L).displayOrder(0).build());
        entityManager.persist(LookItemEntity.builder().id(12L).lookId(1L).productId(701L).displayOrder(0).build());
        assertThatThrownBy(() -> entityManager.flush()).rootCause().hasMessageContaining("uk_look_item_look_id_display_order");
    }

    @Test
    @DisplayName("설명 1001자는 ck_look_description_length 에 걸린다")
    void descriptionLengthCheck() {
        entityManager.persist(lookBuilder(1L).description("가".repeat(1001)).build());
        assertThatThrownBy(() -> entityManager.flush()).rootCause().hasMessageContaining("ck_look_description_length");
    }

    @Test
    @DisplayName("룩 노출 순서가 음수면 ck_look_display_order_non_negative 에 걸린다")
    void displayOrderCheck() {
        entityManager.persist(lookBuilder(1L).displayOrder(-1).build());
        assertThatThrownBy(() -> entityManager.flush()).rootCause().hasMessageContaining("ck_look_display_order_non_negative");
    }

    private static LookEntity lookEntity(long id) {
        return lookBuilder(id).build();
    }

    private static LookEntity.LookEntityBuilder lookBuilder(long id) {
        return LookEntity.builder().id(id).title("룩").imageUrl("https://cdn.example.com/media/a.jpg").visible(true).displayOrder(0);
    }

    private static Look look(long id, int displayOrder, boolean visible) {
        return new Look(id, "룩" + id, null, new LookImage("https://cdn.example.com/media/" + id + ".jpg", 1200, 1600, "대체" + id), visible, displayOrder);
    }

    private static LookItem item(long id, long lookId, long productId, int displayOrder) {
        return new LookItem(id, lookId, productId, displayOrder);
    }

    private static LookContent content(Look look, LookItem... items) {
        return new LookContent(look, List.of(items));
    }
}
