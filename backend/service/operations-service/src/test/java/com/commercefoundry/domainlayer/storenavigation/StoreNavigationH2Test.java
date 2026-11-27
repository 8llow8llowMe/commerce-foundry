package com.commercefoundry.domainlayer.storenavigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.storenavigation.adapter.out.config.StoreNavigationConfig;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.StoreNavigationRepositoryAdapter;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.entity.StoreNavigationEntity;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository.StoreNavigationItemRepository;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository.StoreNavigationRepository;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository.StoreNavigationRevisionRepository;
import com.commercefoundry.domainlayer.storenavigation.application.command.StoreNavigationCommands;
import com.commercefoundry.domainlayer.storenavigation.application.exception.StoreNavigationErrorCode;
import com.commercefoundry.domainlayer.storenavigation.application.exception.StoreNavigationException;
import com.commercefoundry.domainlayer.storenavigation.application.port.out.query.StoreNavigationQueryResults;
import com.commercefoundry.domainlayer.storenavigation.application.service.processor.StoreNavigationProcessor;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent.Item;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent.Text;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationUrlPolicy;
import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@EntityScan(basePackageClasses = StoreNavigationEntity.class)
@EnableJpaRepositories(basePackageClasses = StoreNavigationRepository.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:store-navigation-persistence-599;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:ddl/operations-schema.sql", "spring.jpa.defer-datasource-initialization=false",
    "commerce.store.navigation.allowed-external-hosts=links.example.com"
})
@Import({OperationsServiceFeaturesConfig.class, StoreNavigationConfig.class, StoreNavigationRepositoryAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoreNavigationH2Test {
    private static final Instant NOW = Instant.parse("2026-11-27T00:00:00Z");
    private final AtomicLong ids = new AtomicLong(1000);
    @Autowired StoreNavigationRepositoryAdapter adapter;
    @Autowired StoreNavigationRepository navigations;
    @Autowired StoreNavigationRevisionRepository revisions;
    @Autowired StoreNavigationItemRepository items;
    @Autowired StoreNavigationUrlPolicy urls;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void clear() {
        items.deleteAll();
        revisions.deleteAll();
        navigations.deleteAll();
    }

    @Test
    void snapshotsKeepBilingualHiddenItemsInTreeOrderAndNewRevisionNeverTouchesPublicationPointer() {
        var first = processorAt(NOW).save(7, new StoreNavigationCommands.SaveDraft(null, content("Original")));
        assertThat(first.navigation().version()).isZero();
        assertThat(first.navigation().latestRevision()).isEqualTo(1);
        assertThat(first.navigation().publishedRevision()).isNull();

        var read = adapter.detail().orElseThrow();
        assertThat(read.navigation()).isEqualTo(first.navigation());
        assertThat(read.revision()).isEqualTo(first.revision());
        assertThat(read.revision().content().items()).extracting(Item::key)
            .containsExactly("women", "women-new", "women-skirts", "external", "hidden", "hidden-child");
        var skirts = read.revision().content().items().get(2);
        assertThat(skirts.type()).isEqualTo("CATEGORY");
        assertThat(skirts.categoryId()).isEqualTo(Long.MAX_VALUE);
        assertThat(skirts.url()).isNull();
        assertThat(skirts.labels()).isEqualTo(new Text("치마 Original", "Skirts Original"));
        assertThat(read.revision().content().items()).filteredOn(item -> item.key().startsWith("hidden"))
            .extracting(Item::visible).containsExactly(false, true);

        var second = processorAt(NOW.plusSeconds(1)).save(8, new StoreNavigationCommands.SaveDraft(0L, content("Replacement")));
        assertThat(second.navigation().version()).isEqualTo(1L);
        assertThat(second.navigation().latestRevision()).isEqualTo(2);
        assertThat(second.navigation().publishedRevision()).isNull();
        assertThat(second.revision().createdBy()).isEqualTo(8L);
        assertThat(adapter.detail().orElseThrow().revision()).isEqualTo(second.revision());
        assertThat(revisions.count()).isEqualTo(2);
        assertThat(items.count()).isEqualTo(12);
    }

    @Test
    void identicalSaveCreatesNewRevisionAndEmptyReplacementIsValid() {
        var first = draft(null, content("Example"));
        var second = draft(0L, first.revision().content());
        assertThat(second.navigation().version()).isEqualTo(1L);
        assertThat(second.revision().revisionNumber()).isEqualTo(2);
        assertThat(second.revision().id()).isNotEqualTo(first.revision().id());
        var empty = draft(1L, new StoreNavigationContent(List.of()));
        assertThat(empty.revision().revisionNumber()).isEqualTo(3);
        assertThat(adapter.detail().orElseThrow().revision().content().items()).isEmpty();
        assertThat(items.count()).isEqualTo(12);
    }

    @Test
    void failedItemInsertRollsBackNewRootRevisionAndEarlierItems() {
        // 검증을 건너뛴 중복 key 는 같은 식별자(리비전, key)를 영속성 컨텍스트가 거부해 실패하고, 이미 flush 된 root 를 포함해 전부 롤백된다.
        // DB PK 제약 자체는 StoreNavigationPostgresTest 의 원시 SQL 이 확인한다.
        var duplicate = new ArrayList<>(content("Example").items());
        duplicate.add(duplicate.getFirst());
        assertThatThrownBy(() -> adapter.saveDraft(null, new StoreNavigationContent(duplicate), 2000, 7, NOW))
            .isInstanceOf(RuntimeException.class).isNotInstanceOf(StoreNavigationException.class);
        assertThat(adapter.detail()).isEmpty();
        assertThat(navigations.count()).isZero();
        assertThat(revisions.count()).isZero();
        assertThat(items.count()).isZero();
    }

    @Test
    void failedItemInsertRollsBackUpdatedPointerAndKeepsPreviousDraft() {
        draft(null, content("Original"));
        var before = adapter.detail().orElseThrow();
        // 같은 식별자를 영속성 컨텍스트가 거부해 실패하고, 이미 flush 된 root 버전·포인터 갱신까지 전부 롤백된다.
        var duplicate = new ArrayList<>(content("Replacement").items());
        duplicate.add(duplicate.getLast());
        assertThatThrownBy(() -> adapter.saveDraft(before.navigation().version(), new StoreNavigationContent(duplicate), 3000, 9, NOW))
            .isInstanceOf(RuntimeException.class).isNotInstanceOf(StoreNavigationException.class);
        assertThat(adapter.detail().orElseThrow()).isEqualTo(before);
        assertThat(revisions.count()).isEqualTo(1);
        assertThat(items.count()).isEqualTo(6);
    }

    @Test
    void independentCommitSurvivesOuterRollbackAndConflictDoesNotPoisonCallerTransaction() {
        var outer = new TransactionTemplate(transactions);
        outer.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            var saved = processorAt(NOW).save(7, new StoreNavigationCommands.SaveDraft(null, content("Independent")));
            assertThat(saved.navigation().version()).isZero();
            assertThat(status.isRollbackOnly()).isFalse();
            status.setRollbackOnly();
        });
        var committed = adapter.detail().orElseThrow();
        assertThat(committed.revision().content().items().getFirst().labels().en()).isEqualTo("Women Independent");

        outer.executeWithoutResult(status -> {
            error(() -> processorAt(NOW).save(7, new StoreNavigationCommands.SaveDraft(null, content("Rejected"))),
                StoreNavigationErrorCode.CONFLICT);
            assertThat(status.isRollbackOnly()).isFalse();
        });
        assertThat(adapter.detail()).contains(committed);
        assertThat(revisions.count()).isEqualTo(1);
    }

    @Test
    void staleOrMissingVersionConflictsWithoutPartialWrites() {
        var processor = processorAt(NOW);
        error(() -> processor.save(7, new StoreNavigationCommands.SaveDraft(0L, content("Example"))), StoreNavigationErrorCode.CONFLICT);
        assertThat(navigations.count()).isZero();
        processor.save(7, new StoreNavigationCommands.SaveDraft(null, content("Example")));
        error(() -> processor.save(7, new StoreNavigationCommands.SaveDraft(null, content("Duplicate"))), StoreNavigationErrorCode.CONFLICT);
        error(() -> processor.save(7, new StoreNavigationCommands.SaveDraft(5L, content("Stale"))), StoreNavigationErrorCode.CONFLICT);
        error(() -> processor.save(7, new StoreNavigationCommands.SaveDraft(-1L, content("Negative"))), StoreNavigationErrorCode.VERSION);
        assertThat(adapter.detail().orElseThrow().navigation().version()).isZero();
        assertThat(revisions.count()).isEqualTo(1);
    }

    @Test
    void domainRuleViolationsMapToMatchingErrorCodesBeforeAnyWrite() {
        var processor = processorAt(NOW);
        var external = new Item("x", null, "EXTERNAL", new Text("x", "x"), "https://unapproved.example.com/x", null, 1, true);
        error(() -> processor.save(7, new StoreNavigationCommands.SaveDraft(null, new StoreNavigationContent(List.of(external)))),
            StoreNavigationErrorCode.URL);
        var cycle = List.of(internal("a", "b", 1), internal("b", "a", 1));
        error(() -> processor.save(7, new StoreNavigationCommands.SaveDraft(null, new StoreNavigationContent(cycle))),
            StoreNavigationErrorCode.CYCLE);
        var deep = List.of(internal("a", null, 1), internal("b", "a", 1), internal("c", "b", 1), internal("d", "c", 1));
        error(() -> processor.save(7, new StoreNavigationCommands.SaveDraft(null, new StoreNavigationContent(deep))),
            StoreNavigationErrorCode.DEPTH);
        error(() -> processor.save(7, new StoreNavigationCommands.SaveDraft(null, new StoreNavigationContent(null))),
            StoreNavigationErrorCode.ITEMS);
        assertThat(navigations.count()).isZero();
        assertThat(revisions.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "2026-11-27T00:00:00.123456789Z, 2026-11-27T00:00:00.123456Z",
        "2026-11-27T00:00:00.999999999Z, 2026-11-27T00:00:00.999999Z",
        "2026-11-27T00:00:01.000000001Z, 2026-11-27T00:00:01Z"
    })
    void processorResponseMatchesCommittedMicrosecondPrecisionAndTreeOrder(String input, String expected) {
        var processor = processorAt(Instant.parse(input));
        var unordered = new StoreNavigationContent(content("Example").items().reversed());
        var saved = processor.save(7, new StoreNavigationCommands.SaveDraft(null, unordered));
        assertThat(saved.revision().createdAt()).isEqualTo(Instant.parse(expected));
        assertThat(processor.detail()).isEqualTo(saved);
        var published = processor.publish(8, new StoreNavigationCommands.Publish(saved.navigation().version(), 1));
        assertThat(published.revision().publishedAt()).isEqualTo(Instant.parse(expected));
        assertThat(processor.detail()).isEqualTo(published);
        assertThat(processorAt(Instant.parse(input).plusSeconds(10)).publish(9, new StoreNavigationCommands.Publish(0L, 1))).isEqualTo(published);
    }

    @Test
    void publicationMovesPointerOnceKeepsFirstPublisherAndNewDraftNeverChangesPublicTree() {
        var first = draft(null, content("Original"));
        assertThat(adapter.published()).isEmpty();
        var published = adapter.publish(first.navigation().version(), 1, 8, NOW.plusSeconds(1));
        assertThat(published.navigation().version()).isEqualTo(1L);
        assertThat(published.navigation().publishedRevision()).isEqualTo(1);
        var original = adapter.published().orElseThrow();
        assertThat(original.publishedBy()).isEqualTo(8L);
        assertThat(original.publishedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(original.content()).isEqualTo(first.revision().content());

        var latest = draft(1L, content("Private"));
        assertThat(latest.navigation().publishedRevision()).isEqualTo(1);
        assertThat(adapter.published().orElseThrow()).isEqualTo(original);
        var beforeRetry = adapter.detail().orElseThrow();
        // 현재 게시본의 반복 게시는 오래된 버전이어도 변경 없이 현재 상태를 돌려준다.
        assertThat(adapter.publish(0L, 1, 9, NOW.plusSeconds(9))).isEqualTo(beforeRetry);
        assertThat(adapter.detail().orElseThrow()).isEqualTo(beforeRetry);
        assertThat(adapter.published().orElseThrow()).isEqualTo(original);

        var ko = processorAt(NOW).published("ko").items();
        assertThat(ko).extracting(node -> node.label()).containsExactly("여성 Original", "매거진 Original");
        assertThat(ko.getFirst().description()).isEqualTo("전체 상품 Original");
        assertThat(ko.getFirst().primary()).isTrue();
        assertThat(ko.get(1).shortcut()).isTrue();
        assertThat(ko.getFirst().children()).extracting(node -> node.label()).containsExactly("신상 Original", "치마 Original");
        error(() -> processorAt(NOW).published("fr"), StoreNavigationErrorCode.INVALID_REQUEST);
    }

    @Test
    void firstPublicationRevalidatesStoredDraftAgainstCurrentDeploymentAllowlist() {
        // 저장 당시 허용됐던 호스트가 배포 설정에서 빠진 상황을 포트에 직접 저장해 흉내 낸다.
        var removed = new StoreNavigationContent(List.of(
            new Item("x", null, "EXTERNAL", new Text("예시", "Example"), "https://removed.example.com/help", null, 1, false)));
        draft(null, removed);
        var before = adapter.detail().orElseThrow();
        error(() -> processorAt(NOW).publish(8, new StoreNavigationCommands.Publish(0L, 1)), StoreNavigationErrorCode.URL);
        assertThat(adapter.detail().orElseThrow()).isEqualTo(before);
        assertThat(adapter.published()).isEmpty();
        assertThat(revisions.count()).isEqualTo(1);
    }

    @Test
    void staleVersionNonLatestRevisionAndInvalidCommandsNeverPartiallyChangePublication() {
        var processor = processorAt(NOW);
        error(() -> processor.publish(8, new StoreNavigationCommands.Publish(0L, 1)), StoreNavigationErrorCode.NOT_FOUND);
        processor.save(7, new StoreNavigationCommands.SaveDraft(null, content("Example")));
        processor.save(7, new StoreNavigationCommands.SaveDraft(0L, content("Latest")));
        var before = adapter.detail().orElseThrow();
        error(() -> processor.publish(8, new StoreNavigationCommands.Publish(0L, 2)), StoreNavigationErrorCode.CONFLICT);
        error(() -> processor.publish(8, new StoreNavigationCommands.Publish(1L, 1)), StoreNavigationErrorCode.CONFLICT);
        error(() -> processor.publish(8, new StoreNavigationCommands.Publish(1L, 3)), StoreNavigationErrorCode.CONFLICT);
        error(() -> processor.publish(8, new StoreNavigationCommands.Publish(null, 2)), StoreNavigationErrorCode.VERSION);
        error(() -> processor.publish(8, new StoreNavigationCommands.Publish(1L, 0)), StoreNavigationErrorCode.REVISION);
        error(() -> processor.publish(8, new StoreNavigationCommands.Publish(1L, null)), StoreNavigationErrorCode.REVISION);
        assertThat(adapter.detail().orElseThrow()).isEqualTo(before);
        assertThat(adapter.published()).isEmpty();
        assertThat(revisions.count()).isEqualTo(2);
    }

    private StoreNavigationQueryResults.Detail draft(Long version, StoreNavigationContent content) {
        return adapter.saveDraft(version, content.ordered(), ids.incrementAndGet(), 7, NOW);
    }

    private static Item internal(String key, String parent, int order) {
        return new Item(key, parent, "INTERNAL", new Text("국문 " + key, "en " + key), "/" + key, null, order, true);
    }

    private StoreNavigationContent content(String text) {
        // 입력 순서를 일부러 섞는다. 저장·조회 결과는 트리 전위 순서여야 한다.
        return new StoreNavigationContent(List.of(
            new Item("hidden-child", "hidden", "INTERNAL", new Text("숨김 하위 " + text, "Hidden child " + text), "/sale/kids", null, 1, true),
            new Item("external", null, "EXTERNAL", new Text("매거진 " + text, "Magazine " + text), null, "https://links.example.com/magazine", null,
                2, true, false, true),
            new Item("women-skirts", "women", "CATEGORY", new Text("치마 " + text, "Skirts " + text), null, Long.MAX_VALUE, 2, true),
            new Item("hidden", null, "NONE", new Text("숨김 " + text, "Hidden " + text), null, null, 3, false),
            new Item("women-new", "women", "INTERNAL", new Text("신상 " + text, "New " + text), "/collections/new", null, 1, true),
            new Item("women", null, "NONE", new Text("여성 " + text, "Women " + text), new Text("전체 상품 " + text, "All products " + text),
                null, null, 1, true, true, false)));
    }

    private StoreNavigationProcessor processorAt(Instant now) {
        return new StoreNavigationProcessor(adapter, urls, Clock.fixed(now, ZoneOffset.UTC), new SnowflakeIdGenerator(0, 0));
    }

    private void error(Runnable action, StoreNavigationErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(StoreNavigationException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
