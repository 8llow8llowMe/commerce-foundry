package com.commercefoundry;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.commercefoundry.domainlayer.category.adapter.out.persistence.entity.CategoryEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductCategoryEntity;
import com.commercefoundry.domainlayer.product.application.command.ProductCategoryUpdateCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductHeaderUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCategoryRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real transactions, CAS query and link storage; PostgreSQL subclass inherits every contract. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:product-category-update;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductCategoryUpdateIntegrationTest {
    static final long ID = 820001L;
    static final long FIRST = 820101L;
    static final long SECOND = 820102L;
    static final long THIRD = 820103L;
    @Autowired EntityManager em;
    @Autowired ProductAdminUseCase products;
    @Autowired com.commercefoundry.domainlayer.product.application.port.in.CategoryAdminUseCase categories;
    @MockitoSpyBean com.commercefoundry.domainlayer.category.application.port.out.CategoryRepositoryPort categoryRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean ProductRepositoryPort repository;
    @MockitoSpyBean ProductCategoryRepositoryPort links;
    TransactionTemplate tx;

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> {
            for (long id : List.of(FIRST, SECOND, THIRD)) {
                em.persist(CategoryEntity.builder().id(id).name("category" + id).visible(false).build());
            }
            em.persist(ProductEntity.builder().id(ID).categoryId(FIRST).name("retained").summary("summary").descriptionHtml("<p>body</p>")
                .nameEn("English").summaryEn("English summary").descriptionHtmlEn("<p>English</p>")
                .visible(true).saleStatus(SaleStatus.ON_SALE).salePriceAmount(10000).salePriceCurrency(KRW)
                .listPriceAmount(20000L).listPriceCurrency(KRW).maxQuantityPerOrder(3).customOrder(true).customOrderNotice("notice")
                .customOrderLeadTimeDays(14).preorder(true).expectedShipDate(LocalDate.of(2027, 1, 1)).createdByMemberId(99L).build());
            em.persist(ProductCategoryEntity.builder().id(ID).productId(ID).categoryId(FIRST).displayOrder(8).build());
            em.persist(ProductCategoryEntity.builder().id(ID + 1).productId(ID).categoryId(SECOND).displayOrder(17).build());
        });
    }

    @AfterEach
    void cleanup() {
        reset(repository, links, categoryRepository);
        tx.executeWithoutResult(s -> {
            em.createQuery("delete from ProductCategoryEntity where productId = :id").setParameter("id", ID).executeUpdate();
            em.remove(em.find(ProductEntity.class, ID));
            em.createQuery("delete from CategoryEntity where id in :ids").setParameter("ids", List.of(FIRST, SECOND, THIRD)).executeUpdate();
        });
    }

    @Test
    void replacesDifferenceAndPreservesRetainedLinkAndEveryOtherProductField() {
        Product before = current();
        Instant created = tx.execute(s -> em.find(ProductCategoryEntity.class, ID + 1).getCreatedAt());
        var response = products.updateCategories(command(0, SECOND, List.of(THIRD, THIRD)));
        assertThat(response.productId()).isEqualTo(String.valueOf(ID));
        assertThat(response.categoryId()).isEqualTo(String.valueOf(SECOND));
        assertThat(response.categoryIds()).containsExactly(String.valueOf(SECOND), String.valueOf(THIRD));
        assertThat(response.version()).isEqualTo(1L);
        assertThat(current()).usingRecursiveComparison().ignoringFields("categoryId", "version").isEqualTo(before);
        tx.executeWithoutResult(s -> {
            assertThat(em.find(ProductCategoryEntity.class, ID)).isNull();
            var retained = em.find(ProductCategoryEntity.class, ID + 1);
            assertThat(retained.getDisplayOrder()).isEqualTo(17);
            assertThat(retained.getCreatedAt()).isEqualTo(created);
            var added = em.createQuery("from ProductCategoryEntity where productId = :id and categoryId = :category", ProductCategoryEntity.class)
                .setParameter("id", ID).setParameter("category", THIRD).getSingleResult();
            assertThat(added.getId()).isNotIn(ID, ID + 1);
            assertThat(added.getDisplayOrder()).isZero();
            assertThat(added.getCreatedAt()).isNotNull();
        });
    }

    @Test
    void sameRepresentativeAndIdenticalRequestsAlwaysAdvanceVersion() {
        assertThat(products.updateCategories(command(0, FIRST, List.of(SECOND))).version()).isEqualTo(1L);
        assertThat(products.updateCategories(command(1, FIRST, List.of(SECOND))).version()).isEqualTo(2L);
        assertThat(products.updateCategories(command(2, FIRST, List.of())).categoryIds()).containsExactly(String.valueOf(FIRST));
        assertThat(current().version()).isEqualTo(3L);
    }

    @Test
    void staleVersionMissingAndDeletedProductsAreRejected() {
        expectError(() -> products.updateCategories(command(1, FIRST, List.of())), ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT);
        expectError(() -> products.updateCategories(new ProductCategoryUpdateCommand(ID + 99, 0, 99, FIRST, List.of())), ProductErrorCode.NOT_FOUND_PRODUCT);
        products.delete(ID, 99);
        expectError(() -> products.updateCategories(command(0, FIRST, List.of())), ProductErrorCode.NOT_FOUND_PRODUCT);
    }

    @Test
    void deletedCategoryIsRejectedBeforeAnyMutation() {
        tx.executeWithoutResult(s -> em.remove(em.find(CategoryEntity.class, THIRD)));
        expectError(() -> products.updateCategories(command(0, THIRD, List.of())), ProductErrorCode.PRODUCT_CATEGORY_NOT_FOUND);
        assertThat(current().categoryId()).isEqualTo(FIRST);
        assertThat(current().version()).isZero();
        assertThat(categoryIds()).containsExactly(FIRST, SECOND);
    }

    @Test
    void failureAfterRemovalRollsBackRepresentativeVersionAndLinks() {
        doThrow(new IllegalStateException("injected insert failure")).when(links).insertLinks(eq(ID), anyList(), anyList());
        assertThatThrownBy(() -> products.updateCategories(command(0, THIRD, List.of(SECOND)))).isInstanceOf(IllegalStateException.class);
        assertThat(current().categoryId()).isEqualTo(FIRST);
        assertThat(current().version()).isZero();
        assertThat(categoryIds()).containsExactly(FIRST, SECOND);
    }

    @Test
    void categoryDeletionWaitsForReplacementCommitAndThenRejectsItsNewLink() throws Exception {
        var locked = new CountDownLatch(1);
        var deleteEntered = new CountDownLatch(1);
        var releaseUpdate = new CountDownLatch(1);
        var lockCalls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            boolean deletion = lockCalls.incrementAndGet() == 2;
            if (deletion) deleteEntered.countDown();
            return invocation.callRealMethod();
        }).when(categoryRepository).findAllForUpdate();
        doAnswer(invocation -> {
            locked.countDown();
            await(releaseUpdate);
            return invocation.callRealMethod();
        }).when(repository).updateCategory(ID, 0, THIRD);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var update = executor.submit(() -> products.updateCategories(command(0, THIRD, List.of())));
            await(locked);
            var deletion = executor.submit(() -> outcome(() -> categories.deleteCategory(THIRD, 99)));
            try {
                await(deleteEntered);
                assertThat(update.isDone()).isFalse();
                assertThat(deletion.isDone()).isFalse();
            } finally { releaseUpdate.countDown(); }
            assertThat(update.get(20, TimeUnit.SECONDS).categoryId()).isEqualTo(String.valueOf(THIRD));
            // The new link is visible after DB commit; Spring afterCommit callback scheduling is unrelated.
            assertThat(deletion.get(20, TimeUnit.SECONDS)).isEqualTo("PRODUCT_043");
        }
        assertThat(tx.<CategoryEntity>execute(status -> em.find(CategoryEntity.class, THIRD))).isNotNull();
        assertThat(categoryIds()).containsExactly(THIRD);
    }

    @Test
    void actualConcurrentCategoryUpdatesHaveExactlyOneWinner() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { await(start); return outcome(() -> products.updateCategories(command(0, THIRD, List.of()))); });
            var second = executor.submit(() -> { await(start); return outcome(() -> products.updateCategories(command(0, SECOND, List.of()))); });
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("success", "PRODUCT_010");
        }
        assertThat(current().version()).isEqualTo(1L);
        assertThat(categoryIds()).containsExactly(current().categoryId());
    }

    @Test
    void headerCommittedAfterCategoryReadMakesCasFailWithoutLosingHeader() throws Exception {
        raceWithOtherWrite(() -> products.updateHeader(new ProductHeaderUpdateCommand(ID, 0, 99, "winner", "new", "<p>new</p>",
            SaleStatus.STOP_SELLING, false)));
        assertThat(current().name()).isEqualTo("winner");
        assertThat(current().categoryId()).isEqualTo(FIRST);
        assertThat(categoryIds()).containsExactly(FIRST, SECOND);
    }

    @Test
    void deleteCommittedAfterCategoryReadMakesCasFailWithoutRestoringProduct() throws Exception {
        raceWithOtherWrite(() -> products.delete(ID, 99));
        assertThat(current().isDeleted()).isTrue();
        assertThat(current().categoryId()).isEqualTo(FIRST);
        assertThat(categoryIds()).containsExactly(FIRST, SECOND);
    }

    private void raceWithOtherWrite(Runnable otherWrite) throws Exception {
        var read = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        doAnswer(invocation -> { read.countDown(); await(committed); return invocation.callRealMethod(); })
            .when(repository).updateCategory(ID, 0, THIRD);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var categoryUpdate = executor.submit(() -> outcome(() -> products.updateCategories(command(0, THIRD, List.of()))));
            try { await(read); otherWrite.run(); } finally { committed.countDown(); }
            assertThat(categoryUpdate.get(20, TimeUnit.SECONDS)).isEqualTo("PRODUCT_010");
        }
        assertThat(current().version()).isEqualTo(1L);
    }

    private Product current() { return tx.execute(s -> repository.findAllByIdsIncludingHidden(List.of(ID)).getFirst()); }
    private List<Long> categoryIds() { return tx.execute(s -> links.findCategoryIdsByProductId(ID)); }
    private ProductCategoryUpdateCommand command(long version, long categoryId, List<Long> categories) {
        return new ProductCategoryUpdateCommand(ID, version, 99, categoryId, categories);
    }
    private static void expectError(Runnable action, ProductErrorCode error) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ProductException.class, e -> assertThat(e.getErrorCode()).isEqualTo(error));
    }
    private static String outcome(Runnable action) {
        try { action.run(); return "success"; } catch (ProductException e) { return e.getErrorCode().getCode(); }
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("transaction barrier timed out");
    }
}
