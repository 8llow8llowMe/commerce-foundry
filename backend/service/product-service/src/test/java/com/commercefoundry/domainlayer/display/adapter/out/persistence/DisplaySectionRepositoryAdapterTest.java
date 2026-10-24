package com.commercefoundry.domainlayer.display.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.commercefoundry.domainlayer.display.adapter.out.persistence.entity.DisplayItemEntity;
import com.commercefoundry.domainlayer.display.adapter.out.persistence.entity.DisplaySectionEntity;
import com.commercefoundry.domainlayer.display.application.mapper.DisplaySectionMapperImpl;
import com.commercefoundry.domainlayer.display.application.port.out.DisplaySectionRepositoryPort;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItem;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplayMedia;
import com.commercefoundry.domainlayer.display.domain.model.DisplayMediaType;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySection;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionContent;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
 * PostgreSQL 컨테이너에 DDL 스냅숏을 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한 뒤 실제로 질의한다 — check 제약 · 유니크 키 · 정렬 · 아이템 교체
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
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, DisplaySectionRepositoryAdapter.class, DisplaySectionMapperImpl.class})
class DisplaySectionRepositoryAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DisplaySectionRepositoryPort port;

    @Test
    @DisplayName("섹션은 노출 순서 → 아이디, 아이템은 섹션 → 노출 순서 → 아이디로 읽고, 미디어 · 상품 칸이 domain 으로 돌아온다")
    void readsOrdered() {
        port.insert(content(section(3L, 1), List.of(product(31L, 3L, 0, 700L))));
        port.insert(content(section(2L, 0), List.of(video(22L, 2L, 1), product(21L, 2L, 0, 800L))));
        port.insert(content(section(1L, 1), List.of()));
        entityManager.flush();
        entityManager.clear();

        assertThat(port.findAll()).extracting(DisplaySection::id).containsExactly(2L, 1L, 3L);
        List<DisplayItem> items = port.findItemsBySectionIds(List.of(3L, 2L));
        assertThat(items).extracting(DisplayItem::id).containsExactly(21L, 22L, 31L);
        DisplayMedia media = items.get(1).media();
        assertThat(media.mediaType()).isEqualTo(DisplayMediaType.VIDEO);
        assertThat(media.pcPosterUrl()).isEqualTo("https://cdn.example.com/p.jpg");
        assertThat(items.get(0).productId()).isEqualTo(800L);
        assertThat(port.findItemsBySectionIds(List.of())).isEmpty();
    }

    @Test
    @DisplayName("교체는 섹션 칸을 바꾸고 아이템을 지운 뒤 같은 순서 번호로 다시 넣어도 유니크 키에 걸리지 않는다")
    void replaceItems() {
        port.insert(content(section(1L, 0), List.of(product(11L, 1L, 0, 700L), product(12L, 1L, 1, 701L))));
        entityManager.flush();
        entityManager.clear();

        DisplaySection changed = new DisplaySection(1L, DisplaySectionType.PRODUCT_PICK, "바뀐 제목", null, "본문", "/products", "보기", 0, false, START, START.plusSeconds(60));
        port.replace(content(changed, List.of(product(13L, 1L, 0, 702L))));
        entityManager.flush();
        entityManager.clear();

        DisplaySection reloaded = port.findById(1L).orElseThrow();
        assertThat(reloaded.title()).isEqualTo("바뀐 제목");
        assertThat(reloaded.visible()).isFalse();
        assertThat(reloaded.endAt()).isEqualTo(START.plusSeconds(60));
        assertThat(port.findItemsBySectionIds(List.of(1L))).extracting(DisplayItem::id).containsExactly(13L);
    }

    @Test
    @DisplayName("삭제는 아이템까지 지우고, 순서 변경은 바꾼 순서로 다시 읽힌다")
    void deleteAndReorder() {
        port.insert(content(section(1L, 0), List.of(product(11L, 1L, 0, 700L))));
        port.insert(content(section(2L, 1), List.of()));
        entityManager.flush();

        port.findAllForUpdate();
        port.changeDisplayOrders(Map.of(1L, 1, 2L, 0));
        entityManager.flush();
        entityManager.clear();
        assertThat(port.findAll()).extracting(DisplaySection::id).containsExactly(2L, 1L);

        port.delete(1L);
        entityManager.flush();
        entityManager.clear();
        assertThat(port.findById(1L)).isEmpty();
        assertThat(port.findItemsBySectionIds(List.of(1L))).isEmpty();
    }

    @Test
    @DisplayName("잠금 조회는 트랜잭션 안에서 전체 섹션을 노출 순서로 돌려준다")
    void findAllForUpdate() {
        port.insert(content(section(1L, 1), List.of()));
        port.insert(content(section(2L, 0), List.of()));
        entityManager.flush();
        entityManager.clear();

        assertThat(port.findAllForUpdate()).extracting(DisplaySection::id).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("같은 섹션에 같은 순서 번호의 아이템은 uk_display_item_section_id_display_order 에 걸린다")
    void uniqueItemOrder() {
        entityManager.persist(sectionEntity(1L));
        entityManager.persist(DisplayItemEntity.builder().id(11L).sectionId(1L).displayOrder(0).itemType(DisplayItemType.PRODUCT).productId(700L).build());
        entityManager.persist(DisplayItemEntity.builder().id(12L).sectionId(1L).displayOrder(0).itemType(DisplayItemType.PRODUCT).productId(701L).build());

        assertThatThrownBy(() -> entityManager.flush()).rootCause().hasMessageContaining("uk_display_item_section_id_display_order");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("sectionViolations")
    @DisplayName("섹션 check 제약 — 위반마다 그 제약 이름으로 막힌다")
    void sectionCheck(String caseName, String constraint, DisplaySectionEntity entity) {
        entityManager.persist(entity);

        assertThatThrownBy(() -> entityManager.flush()).as(caseName).rootCause().hasMessageContaining(constraint);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("itemViolations")
    @DisplayName("아이템 check 제약 — 한 제약만 어기는 행마다 그 제약 이름으로 막힌다")
    void itemCheck(String caseName, String constraint, DisplayItemEntity entity) {
        entityManager.persist(sectionEntity(1L));
        entityManager.persist(entity);

        assertThatThrownBy(() -> entityManager.flush()).as(caseName).rootCause().hasMessageContaining(constraint);
    }

    static Stream<Arguments> sectionViolations() {
        return Stream.of(
            arguments("종료 = 시작", "ck_display_section_period", sectionBuilder().startAt(START).endAt(START).build()),
            arguments("종료 < 시작", "ck_display_section_period", sectionBuilder().startAt(START).endAt(START.minusSeconds(1)).build()),
            arguments("본문 2001자", "ck_display_section_body_length", sectionBuilder().body("가".repeat(2001)).build()),
            arguments("노출 순서 -1", "ck_display_section_display_order_non_negative", sectionBuilder().displayOrder(-1).build())
        );
    }

    static Stream<Arguments> itemViolations() {
        return Stream.of(
            arguments("IMAGE + pc_poster", "ck_display_item_video_poster", image().pcPosterUrl("https://cdn.example.com/p.jpg").build()),
            arguments("IMAGE + mobile_poster", "ck_display_item_video_poster", image().mobilePosterUrl("https://cdn.example.com/p.jpg").build()),
            arguments("VIDEO 포스터 없음", "ck_display_item_video_poster", image().mediaType(DisplayMediaType.VIDEO).build()),
            arguments("MEDIA + pc_url null", "ck_display_item_media_fields", image().pcUrl(null).build()),
            arguments("MEDIA + media_type null", "ck_display_item_media_fields", image().mediaType(null).build()),
            arguments("MEDIA + product_id", "ck_display_item_media_fields", image().productId(700L).build()),
            arguments("PRODUCT + alt_text", "ck_display_item_product_fields", productItem().altText("대체").build()),
            arguments("PRODUCT + pc_url", "ck_display_item_product_fields", productItem().pcUrl("https://cdn.example.com/a.jpg").build()),
            arguments("PRODUCT + product_id null", "ck_display_item_product_fields", productItem().productId(null).build()),
            arguments("노출 순서 -1", "ck_display_item_display_order_non_negative", productItem().displayOrder(-1).build())
        );
    }

    private static DisplaySectionEntity.DisplaySectionEntityBuilder sectionBuilder() {
        return DisplaySectionEntity.builder().id(1L).sectionType(DisplaySectionType.STORY).displayOrder(0).visible(true);
    }

    private static DisplayItemEntity.DisplayItemEntityBuilder image() {
        return DisplayItemEntity.builder().id(11L).sectionId(1L).displayOrder(0).itemType(DisplayItemType.MEDIA).mediaType(DisplayMediaType.IMAGE)
            .pcUrl("https://cdn.example.com/a.jpg");
    }

    private static DisplayItemEntity.DisplayItemEntityBuilder productItem() {
        return DisplayItemEntity.builder().id(11L).sectionId(1L).displayOrder(0).itemType(DisplayItemType.PRODUCT).productId(700L);
    }

    private static DisplaySectionEntity sectionEntity(long id) {
        return DisplaySectionEntity.builder().id(id).sectionType(DisplaySectionType.LOOK).displayOrder(0).visible(true).build();
    }

    private static DisplaySection section(long id, int displayOrder) {
        return new DisplaySection(id, DisplaySectionType.LOOK, "섹션" + id, null, null, null, null, displayOrder, true, null, null);
    }

    private static DisplaySectionContent content(DisplaySection section, List<DisplayItem> items) {
        return new DisplaySectionContent(section, items);
    }

    private static DisplayItem product(long id, long sectionId, int displayOrder, long productId) {
        return new DisplayItem(id, sectionId, displayOrder, DisplayItemType.PRODUCT, null, productId, "캡션");
    }

    private static DisplayItem video(long id, long sectionId, int displayOrder) {
        DisplayMedia media = new DisplayMedia(DisplayMediaType.VIDEO, "https://cdn.example.com/v.mp4", null, "https://cdn.example.com/p.jpg", null, "영상");
        return new DisplayItem(id, sectionId, displayOrder, DisplayItemType.MEDIA, media, null, null);
    }
}
