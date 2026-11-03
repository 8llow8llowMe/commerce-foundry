package com.commercefoundry;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.*;
import com.commercefoundry.domainlayer.category.adapter.out.persistence.entity.CategoryEntity;
import com.commercefoundry.domainlayer.product.application.command.*;
import com.commercefoundry.domainlayer.product.application.exception.*;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.application.port.out.*;
import com.commercefoundry.domainlayer.product.domain.model.*;
import com.commercefoundry.shared.commerce.Money;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real constraints and transactions, inherited unchanged by the PostgreSQL suite. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:product-image-edit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductImagesUpdateIntegrationTest {
    static final long ID = 830001L, THUMB = 830011L, FIRST = 830012L, SECOND = 830013L, VIDEO = 830014L, FOREIGN = 830015L;
    @Autowired EntityManager em;
    @Autowired ProductAdminUseCase products;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean ProductImageStoragePort storage;
    @MockitoSpyBean ProductRepositoryPort repository;
    @MockitoSpyBean ProductImageRepositoryPort images;
    TransactionTemplate tx;

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(s -> {
            em.persist(CategoryEntity.builder().id(ID).name("images").build());
            for (long id : List.of(ID, ID + 1)) em.persist(ProductEntity.builder().id(id).categoryId(ID).name("retained")
                .visible(false).saleStatus(SaleStatus.ON_SALE).salePriceAmount(10000).salePriceCurrency(KRW).build());
            em.persist(image(THUMB, ID, ImageType.THUMBNAIL, 0));
            em.persist(image(FIRST, ID, ImageType.GALLERY, 1));
            em.persist(image(SECOND, ID, ImageType.GALLERY, 2));
            em.persist(image(VIDEO, ID, ImageType.VIDEO, 1));
            em.persist(image(FOREIGN, ID + 1, ImageType.GALLERY, 1));
        });
    }

    @AfterEach
    void cleanup() {
        reset(images, repository);
        tx.executeWithoutResult(s -> {
            em.createQuery("delete from ProductImageEntity where productId in :ids").setParameter("ids", List.of(ID, ID + 1)).executeUpdate();
            em.createQuery("delete from ProductCategoryEntity where productId = :id").setParameter("id", ID).executeUpdate();
            em.createQuery("delete from ProductEntity where id in :ids").setParameter("ids", List.of(ID, ID + 1)).executeUpdate();
            em.remove(em.find(CategoryEntity.class, ID));
        });
    }

    @Test
    void swapsGalleryAndThumbnailPreservingIdentityObjectMetadataAndCreatedAt() {
        var before = snapshot();
        var productBefore = tx.execute(s -> repository.findAllByIdsIncludingHidden(List.of(ID)).getFirst());
        var updatedBefore = tx.execute(s -> em.find(ProductEntity.class, ID).getUpdatedAt());
        var response = products.updateImages(command(0, existing(SECOND, ImageType.THUMBNAIL), existing(FIRST, ImageType.GALLERY), existing(THUMB, ImageType.GALLERY)));
        assertThat(response.productId()).isEqualTo(String.valueOf(ID));
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.images()).hasSize(4);
        tx.executeWithoutResult(s -> {
            assertThat(repository.findAllByIdsIncludingHidden(List.of(ID)).getFirst()).usingRecursiveComparison()
                .ignoringFields("version").isEqualTo(productBefore);
            assertThat(em.find(ProductEntity.class, ID).getUpdatedAt()).isAfter(updatedBefore);
            for (var old : before) {
                var now = em.find(ProductImageEntity.class, old.getId());
                assertThat(now.getCreatedAt()).isEqualTo(old.getCreatedAt());
                assertThat(now.getUrl()).isEqualTo(old.getUrl());
                assertThat(now.getStorageKey()).isEqualTo(old.getStorageKey());
                assertThat(now.getWidth()).isEqualTo(old.getWidth());
                assertThat(now.getHeight()).isEqualTo(old.getHeight());
            }
            assertThat(em.find(ProductImageEntity.class, SECOND).getImageType()).isEqualTo(ImageType.THUMBNAIL);
            assertThat(em.find(ProductImageEntity.class, THUMB).getDisplayOrder()).isEqualTo(3);
            assertThat(em.find(ProductImageEntity.class, VIDEO)).usingRecursiveComparison()
                .isEqualTo(before.stream().filter(i -> i.getId() == VIDEO).findFirst().orElseThrow());
        });
        verifyNoInteractions(storage);
    }

    @Test
    void gallerySwapAndLegacyNegativeOrdersAreSafe() {
        tx.executeWithoutResult(s -> em.createQuery("update ProductImageEntity set displayOrder = :order where id = :id")
            .setParameter("order", Integer.MIN_VALUE).setParameter("id", FIRST).executeUpdate());
        products.updateImages(command(0, existing(SECOND, ImageType.GALLERY), existing(FIRST, ImageType.GALLERY), existing(THUMB, ImageType.THUMBNAIL)));
        products.updateImages(command(1, existing(FIRST, ImageType.GALLERY), existing(SECOND, ImageType.GALLERY), existing(THUMB, ImageType.THUMBNAIL)));
        assertThat(tx.<Integer>execute(s -> em.find(ProductImageEntity.class, FIRST).getDisplayOrder())).isEqualTo(1);
        tx.executeWithoutResult(s -> em.createQuery("update ProductImageEntity set displayOrder = -1 where id = :id").setParameter("id", FIRST).executeUpdate());
        products.updateImages(command(2, existing(SECOND, ImageType.GALLERY), existing(FIRST, ImageType.GALLERY)));
        assertThat(currentVersion()).isEqualTo(3);
        verifyNoInteractions(storage);
    }

    @Test
    void newSubsetMapsToWholeRequestOrderAndEmptyRemovesOnlyStillConnections() {
        uploaded();
        products.updateImages(command(0, existing(FIRST, ImageType.GALLERY), added(upload(99), ImageType.THUMBNAIL), existing(SECOND, ImageType.GALLERY)));
        var fresh = snapshot();
        assertThat(fresh).hasSize(4);
        var copied = fresh.stream().filter(i -> i.getId() != VIDEO && i.getId() != FIRST && i.getId() != SECOND).findFirst().orElseThrow();
        assertThat(copied.getImageType()).isEqualTo(ImageType.THUMBNAIL);
        assertThat(copied.getStorageKey()).startsWith("products/" + ID + "/");
        assertThat(tx.<Integer>execute(s -> em.find(ProductImageEntity.class, SECOND).getDisplayOrder())).isEqualTo(3);
        products.updateImages(command(1));
        assertThat(snapshot()).extracting(ProductImageEntity::getId).containsExactly(VIDEO);
        products.updateImages(command(2));
        assertThat(currentVersion()).isEqualTo(3);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void storageDisabledStillAllowsIdenticalReorderAndDeleteWithoutExternalCalls() {
        // test profile leaves storage.enabled=false; invoking the port would fail closed.
        when(storage.attach(any(), anyString())).thenThrow(new ProductException(ProductErrorCode.IMAGE_UPLOAD_DISABLED));
        var unchanged = command(0, existing(THUMB, ImageType.THUMBNAIL), existing(FIRST, ImageType.GALLERY), existing(SECOND, ImageType.GALLERY));
        products.updateImages(unchanged);
        products.updateImages(command(1, existing(THUMB, ImageType.THUMBNAIL), existing(FIRST, ImageType.GALLERY), existing(SECOND, ImageType.GALLERY)));
        products.updateImages(command(2));
        assertThat(currentVersion()).isEqualTo(3);
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsInvalidReferencesAndCompositionBeforeStorageIo() {
        String key = upload(99);
        var invalid = List.of(
            command(0, existing(FOREIGN, ImageType.GALLERY)), command(0, existing(VIDEO, ImageType.GALLERY)),
            command(0, existing(FIRST, ImageType.GALLERY), existing(FIRST, ImageType.GALLERY)),
            command(0, added(key, ImageType.GALLERY), added(key, ImageType.GALLERY)),
            command(0, new ProductImagesUpdateCommand.Image(FIRST, key, ImageType.GALLERY, null)),
            command(0, new ProductImagesUpdateCommand.Image(null, null, ImageType.GALLERY, null)),
            command(0, existing(FIRST, ImageType.VIDEO)),
            command(0, existing(FIRST, ImageType.THUMBNAIL), existing(SECOND, ImageType.THUMBNAIL)),
            command(0, new ProductImagesUpdateCommand.Image(FIRST, null, ImageType.GALLERY, "a".repeat(201))));
        for (var c : invalid) error(() -> products.updateImages(c), ProductErrorCode.IMAGE_CONFIGURATION_INVALID);
        error(() -> products.updateImages(command(0, added(key, ImageType.GALLERY), added(upload(100), ImageType.GALLERY))), ProductErrorCode.IMAGE_UPLOAD_NOT_FOUND);
        error(() -> products.updateImages(command(1)), ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT);
        error(() -> products.updateImages(new ProductImagesUpdateCommand(ID + 999, 0, 99, List.of())), ProductErrorCode.NOT_FOUND_PRODUCT);
        products.delete(ID, 99);
        error(() -> products.updateImages(command(0)), ProductErrorCode.NOT_FOUND_PRODUCT);
        verifyNoInteractions(storage);
    }

    @Test
    void partialCopyFailureCleansOnlyAttemptedNewTargetsOutsideTransactions() {
        var targets = new java.util.ArrayList<String>();
        when(storage.attach(any(), anyString())).thenAnswer(c -> {
            assertNoTransaction(); targets.add(c.getArgument(1));
            if (targets.size() == 2) throw new IllegalStateException("copy failed");
            return "https://media.example.com/" + c.getArgument(1);
        });
        doAnswer(c -> { assertNoTransaction(); return null; }).when(storage).delete(anyString());
        assertThatThrownBy(() -> products.updateImages(command(0, added(upload(99), ImageType.GALLERY), added(upload(99), ImageType.GALLERY))))
            .isInstanceOf(IllegalStateException.class);
        for (String target : targets) verify(storage).delete(target);
        verify(storage, times(2)).delete(anyString());
        assertThat(currentVersion()).isZero();
        assertThat(snapshot()).hasSize(4);
    }

    @Test
    void failureAfterCasAndDeleteRollsBackEverythingAndCompensatesOnlyNewCopy() {
        uploaded();
        var before = snapshot();
        doThrow(new IllegalStateException("injected persistence failure")).when(images).insertAll(anyList());
        doAnswer(c -> { assertNoTransaction(); return null; }).when(storage).delete(anyString());
        assertThatThrownBy(() -> products.updateImages(command(0, added(upload(99), ImageType.THUMBNAIL))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(currentVersion()).isZero();
        assertThat(snapshot()).usingRecursiveComparison().isEqualTo(before);
        verify(storage).delete(startsWith("products/" + ID + "/"));
        verify(storage, never()).delete(startsWith("legacy/"));
    }

    @Test
    void simultaneousSameVersionHasExactlyOneWinner() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> { await(start); return outcome(() -> products.updateImages(command(0, existing(FIRST, ImageType.GALLERY)))); });
            var second = pool.submit(() -> { await(start); return outcome(() -> products.updateImages(command(0, existing(SECOND, ImageType.GALLERY)))); });
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder("success", "PRODUCT_010");
        }
        assertThat(currentVersion()).isEqualTo(1);
    }

    @Test
    void headerCategoryPricingAndDeletionCommittedBeforeImageCasWin() throws Exception {
        race(0, () -> products.updateHeader(new ProductHeaderUpdateCommand(ID, 0, 99, "winner", null, null, SaleStatus.ON_SALE, false)));
        reset(repository);
        race(1, () -> products.updateCategories(new ProductCategoryUpdateCommand(ID, 1, 99, ID, List.of())));
        reset(repository);
        race(2, () -> products.updatePricing(new ProductPricingUpdateCommand(ID, 2, 99, Money.of(10000, KRW), Money.of(20000, KRW))));
        reset(repository);
        race(3, () -> products.delete(ID, 99));
        assertThat(snapshot()).hasSize(4);
        verifyNoInteractions(storage);
    }

    private void race(long version, Runnable other) throws Exception {
        var ready = new CountDownLatch(1); var committed = new CountDownLatch(1);
        doAnswer(c -> { ready.countDown(); await(committed); return c.callRealMethod(); }).when(repository).bumpVersion(ID, version);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var editing = pool.submit(() -> outcome(() -> products.updateImages(command(version))));
            try { await(ready); other.run(); } finally { committed.countDown(); }
            assertThat(editing.get(20, TimeUnit.SECONDS)).isEqualTo("PRODUCT_010");
        }
        assertThat(currentVersion()).isEqualTo(version + 1);
    }
    private List<ProductImageEntity> snapshot() {
        return tx.execute(s -> em.createQuery("from ProductImageEntity where productId = :id order by id", ProductImageEntity.class).setParameter("id", ID).getResultList());
    }
    private long currentVersion() { return tx.execute(s -> em.find(ProductEntity.class, ID).getVersion()); }
    private ProductImageEntity image(long id, long product, ImageType type, int order) {
        return ProductImageEntity.builder().id(id).productId(product).imageType(type).displayOrder(order).url("https://media.example.com/" + id)
            .storageKey("legacy/shared-" + id).width(100).height(200).altText("retained").posterUrl(type == ImageType.VIDEO ? "https://media.example.com/poster" : null).build();
    }
    private ProductImagesUpdateCommand command(long version, ProductImagesUpdateCommand.Image... inputs) {
        return new ProductImagesUpdateCommand(ID, version, 99, List.of(inputs));
    }
    private ProductImagesUpdateCommand.Image existing(long id, ImageType type) { return new ProductImagesUpdateCommand.Image(id, null, type, "edited"); }
    private ProductImagesUpdateCommand.Image added(String key, ImageType type) { return new ProductImagesUpdateCommand.Image(null, key, type, null); }
    private String upload(long owner) { return ProductImageUploadKey.issue(owner, ProductImageFormat.PNG).value(); }
    private void uploaded() { when(storage.attach(any(), anyString())).thenAnswer(c -> { assertNoTransaction(); return "https://media.example.com/" + c.getArgument(1); }); }
    private void assertNoTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(em.getEntityManagerFactory())).isFalse();
    }
    private static void error(Runnable call, ProductErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ProductException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
    private static String outcome(Runnable call) { try { call.run(); return "success"; } catch (ProductException e) { return e.getErrorCode().getCode(); } }
    private static void await(CountDownLatch latch) throws InterruptedException { if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout"); }
}
