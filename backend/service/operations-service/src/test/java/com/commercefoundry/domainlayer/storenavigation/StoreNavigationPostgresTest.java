package com.commercefoundry.domainlayer.storenavigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.storenavigation.adapter.out.config.StoreNavigationConfig;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.StoreNavigationRepositoryAdapter;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.entity.StoreNavigationEntity;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository.StoreNavigationItemRepository;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository.StoreNavigationRepository;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository.StoreNavigationRevisionRepository;
import com.commercefoundry.domainlayer.storenavigation.application.exception.StoreNavigationErrorCode;
import com.commercefoundry.domainlayer.storenavigation.application.exception.StoreNavigationException;
import com.commercefoundry.domainlayer.storenavigation.application.port.out.query.StoreNavigationQueryResults;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent.Item;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent.Text;
import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 실제 PostgreSQL 에서 동시 저장 경합(409)·롤백과 check 제약을 확인한다. 게시 경합은 게시 API 와 함께 더한다. */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@EntityScan(basePackageClasses = StoreNavigationEntity.class)
@EnableJpaRepositories(basePackageClasses = StoreNavigationRepository.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.sql.init.mode=never", "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
    "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '10s'",
    "commerce.store.navigation.allowed-external-hosts=links.example.com"
})
@Import({OperationsServiceFeaturesConfig.class, StoreNavigationConfig.class, StoreNavigationRepositoryAdapter.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoreNavigationPostgresTest {
    private static final Instant NOW = Instant.parse("2026-11-27T00:00:00.123456Z");
    private static final long REVISION_A = 5001;
    private static final long REVISION_B = 5002;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    @Autowired StoreNavigationRepositoryAdapter adapter;
    @Autowired StoreNavigationRepository navigations;
    @Autowired StoreNavigationRevisionRepository revisions;
    @Autowired StoreNavigationItemRepository items;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clear() {
        items.deleteAllInBatch();
        revisions.deleteAllInBatch();
        navigations.deleteAllInBatch();
    }

    @Test
    void concurrentFirstSavesCommitOneSnapshotAndRejectTheOtherWith409() throws Exception {
        var outcomes = race(() -> draft(null, REVISION_A, content("First A")), () -> draft(null, REVISION_B, content("First B")));
        var result = adapter.detail().orElseThrow();
        assertThat(result.navigation().version()).isZero();
        assertThat(result.navigation().latestRevision()).isEqualTo(1);
        assertThat(result.navigation().publishedRevision()).isNull();
        long winner = outcomes.get(0) ? REVISION_A : REVISION_B;
        long loser = outcomes.get(0) ? REVISION_B : REVISION_A;
        assertThat(result.revision().id()).isEqualTo(winner);
        assertThat(result.revision().content().items().getFirst().labels().en()).isEqualTo("Women First " + (outcomes.get(0) ? "A" : "B"));
        counts(1, 3);
        assertThat(itemRows(winner)).isEqualTo(3);
        assertThat(itemRows(loser)).isZero();
    }

    @Test
    void concurrentDraftsWithSameVersionCommitOneNewRevisionAndDiscardLosersItems() throws Exception {
        draft(null, 4000, content("Initial"));
        var original = adapter.detail().orElseThrow().revision();
        var outcomes = race(() -> draft(0L, REVISION_A, content("Draft A")), () -> draft(0L, REVISION_B, content("Draft B")));
        var result = adapter.detail().orElseThrow();
        assertThat(result.navigation().version()).isEqualTo(1L);
        assertThat(result.navigation().latestRevision()).isEqualTo(2);
        assertThat(result.navigation().publishedRevision()).isNull();
        long winner = outcomes.get(0) ? REVISION_A : REVISION_B;
        long loser = outcomes.get(0) ? REVISION_B : REVISION_A;
        assertThat(result.revision().id()).isEqualTo(winner);
        assertThat(result.revision().revisionNumber()).isEqualTo(2);
        counts(2, 6);
        assertThat(itemRows(original.id())).isEqualTo(3);
        assertThat(itemRows(winner)).isEqualTo(3);
        assertThat(itemRows(loser)).isZero();
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("constraintViolations")
    void postgresRejectsInvalidItemRowsWithoutMutatingCommittedState(String sql, String constraint, String sqlState) {
        draft(null, 4000, content("Constraints"));
        var before = adapter.detail().orElseThrow();
        assertThatThrownBy(() -> jdbc.update(sql)).satisfies(failure -> databaseFailure(failure, constraint, sqlState));
        assertThat(adapter.detail()).contains(before);
        counts(1, 3);
    }

    private static Stream<Arguments> constraintViolations() {
        String insert = "insert into operations.store_navigation_item (revision_id, item_key, parent_key, type, label_ko, label_en, url, category_id,"
            + " display_order, visible) select id, ";
        String from = " from operations.store_navigation_revision";
        // 알 수 없는 type 은 _type 과 유형별 형태 제약 _target 을 함께 어긴다. 어느 쪽이 먼저 보고되는지는 평가 순서에 맡기고 접두어로 본다.
        return Stream.of(
            Arguments.of(insert + "'bad-type', null, 'MAILTO', '잘못', 'Bad', '/bad', null, 9, true" + from, "ck_store_navigation_item_t", "23514"),
            Arguments.of(insert + "'zero-category', null, 'CATEGORY', '영', 'Zero', null, 0, 9, true" + from,
                "ck_store_navigation_item_target", "23514"),
            Arguments.of(insert + "'too-far', null, 'INTERNAL', '먼', 'Far', '/far', null, 1000, true" + from,
                "ck_store_navigation_item_order", "23514"),
            Arguments.of(insert + "'self', 'self', 'NONE', '자기', 'Self', null, null, 9, true" + from, "ck_store_navigation_item_parent", "23514"),
            Arguments.of(insert + "'women', null, 'NONE', '중복', 'Duplicate', null, null, 9, true" + from, "store_navigation_item_pkey", "23505"),
            Arguments.of("update operations.store_navigation set id = 2", "ck_store_navigation_singleton", "23514"),
            Arguments.of("update operations.store_navigation set published_revision = 2", "ck_store_navigation_pointers", "23514"),
            Arguments.of("update operations.store_navigation_revision set published_by = 8", "ck_store_navigation_revision_publication", "23514"),
            Arguments.of("update operations.store_navigation_item set description_ko = '설명' where item_key = 'magazine'",
                "ck_store_navigation_item_description", "23514"),
            Arguments.of("update operations.store_navigation_item set primary_menu = true where item_key = 'women-skirts'",
                "ck_store_navigation_item_placement", "23514"),
            Arguments.of("update operations.store_navigation_item set shortcut_menu = true where item_key = 'women-skirts'",
                "ck_store_navigation_item_placement", "23514")
        );
    }

    @Test
    void omittedPlacementColumnsDefaultToFalseInDatabase() {
        draft(null, 4000, content("Defaults"));
        jdbc.update("insert into operations.store_navigation_item (revision_id, item_key, parent_key, type, label_ko, label_en, url, category_id,"
            + " display_order, visible) select id, 'extra', null, 'NONE', '추가', 'Extra', null, null, 9, true from operations.store_navigation_revision");
        assertThat(jdbc.queryForObject("select primary_menu or shortcut_menu from operations.store_navigation_item where item_key = 'extra'",
            Boolean.class)).isFalse();
    }

    @Test
    void draftAndPublishRaceCommitExactlyOneCompleteChange() throws Exception {
        draft(null, 4000, content("Initial"));
        var original = adapter.detail().orElseThrow().revision();
        var outcomes = race(() -> draft(0L, REVISION_A, content("Next")), () -> adapter.publish(0L, 1, 8, NOW.plusSeconds(1)));
        var result = adapter.detail().orElseThrow();
        assertThat(result.navigation().version()).isEqualTo(1L);
        if (outcomes.get(0)) {
            assertThat(result.navigation().latestRevision()).isEqualTo(2);
            assertThat(result.navigation().publishedRevision()).isNull();
            assertThat(adapter.published()).isEmpty();
            counts(2, 6);
        } else {
            assertThat(result.navigation().latestRevision()).isEqualTo(1);
            assertThat(result.navigation().publishedRevision()).isEqualTo(1);
            assertThat(adapter.published()).contains(result.revision());
            assertThat(result.revision().content()).isEqualTo(original.content());
            assertThat(result.revision().publishedBy()).isEqualTo(8L);
            counts(1, 3);
            assertThat(itemRows(REVISION_A)).isZero();
        }
    }

    @Test
    void concurrentPublicationsHaveOne409AndRetryRetainsFirstPublisherAndVersion() throws Exception {
        draft(null, 4000, content("Initial"));
        var outcomes = race(() -> adapter.publish(0L, 1, 8, NOW.plusSeconds(1)), () -> adapter.publish(0L, 1, 9, NOW.plusSeconds(2)));
        var before = adapter.detail().orElseThrow();
        assertThat(before.navigation().version()).isEqualTo(1L);
        assertThat(before.navigation().publishedRevision()).isEqualTo(1);
        assertThat(before.revision().publishedBy()).isEqualTo(outcomes.get(0) ? 8L : 9L);
        assertThat(before.revision().publishedAt()).isEqualTo(NOW.plusSeconds(outcomes.get(0) ? 1 : 2));
        assertThat(adapter.publish(0L, 1, 10, NOW.plusSeconds(3))).isEqualTo(before);
        assertThat(adapter.detail()).contains(before);
        assertThat(adapter.published()).contains(before.revision());
        counts(1, 3);
    }

    private long itemRows(long revisionId) {
        return jdbc.queryForObject("select count(*) from operations.store_navigation_item where revision_id = ?", Long.class, revisionId);
    }

    private void databaseFailure(Throwable failure, String constraint, String sqlState) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOfSatisfying(SQLException.class, sql -> {
            assertThat(sql.getSQLState()).isEqualTo(sqlState);
            assertThat(sql.getMessage()).contains(constraint);
        });
    }

    private StoreNavigationQueryResults.Detail draft(Long version, long revisionId, StoreNavigationContent content) {
        return adapter.saveDraft(version, content.ordered(), revisionId, 7, NOW);
    }

    private StoreNavigationContent content(String text) {
        return new StoreNavigationContent(List.of(
            new Item("women-skirts", "women", "CATEGORY", new Text("치마 " + text, "Skirts " + text), null, 42L, 2, true),
            new Item("magazine", null, "EXTERNAL", new Text("매거진 " + text, "Magazine " + text), "https://links.example.com/magazine", null, 2, false),
            new Item("women", null, "NONE", new Text("여성 " + text, "Women " + text), null, null, 1, true)));
    }

    private void counts(long revisionCount, long itemCount) {
        assertThat(navigations.count()).isEqualTo(1);
        assertThat(revisions.count()).isEqualTo(revisionCount);
        assertThat(items.count()).isEqualTo(itemCount);
    }

    private List<Boolean> race(Callable<?> first, Callable<?> second) throws Exception {
        var bothRead = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        Advised repository = (Advised) navigations;
        MethodInterceptor synchronizeRootReads = invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("findById") && Long.valueOf(1).equals(invocation.getArguments()[0])) {
                // 두 트랜잭션이 모두 root 를 읽은 뒤에 쓰게 맞춘다.
                bothRead.countDown();
                assertThat(release.await(15, TimeUnit.SECONDS)).as("release both root readers").isTrue();
            }
            return result;
        };
        var executor = Executors.newFixedThreadPool(2);
        repository.addAdvice(0, synchronizeRootReads);
        try {
            var a = executor.submit(() -> outcome(first));
            var b = executor.submit(() -> outcome(second));
            assertThat(bothRead.await(15, TimeUnit.SECONDS)).as("both transactions reached their root read").isTrue();
            release.countDown();
            var outcomes = List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
            return outcomes;
        } finally {
            release.countDown();
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).as("race workers stopped before cleanup").isTrue();
            } finally {
                repository.removeAdvice(synchronizeRootReads);
            }
        }
    }

    private boolean outcome(Callable<?> operation) throws Exception {
        try {
            operation.call();
            return true;
        } catch (StoreNavigationException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(StoreNavigationErrorCode.CONFLICT);
            assertThat(exception.getErrorCode().getCode()).isEqualTo("STORE_NAV_020");
            assertThat(exception.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
            return false;
        }
    }
}
