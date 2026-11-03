package com.commercefoundry;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionGroupEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionValueEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationItemEntity;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import com.commercefoundry.domainlayer.product.application.port.in.ProductWebUseCase;
import com.commercefoundry.domainlayer.product.application.port.in.SkuInternalUseCase;
import com.commercefoundry.domainlayer.skucost.adapter.out.persistence.entity.SkuCostEntity;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.command.ProductOptionsUpdateCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductOptionsUpdateCommand.Group;
import com.commercefoundry.domainlayer.product.application.command.ProductOptionsUpdateCommand.Value;
import com.commercefoundry.domainlayer.product.application.command.ProductHeaderUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:product-option-label-edit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductOptionsUpdateIntegrationTest {
    static final long ID = 870001, G1 = 870011, G2 = 870012, V1 = 870021, V2 = 870022, V3 = 870023, SKU = 870031;
    @Autowired EntityManager em;
    @Autowired ProductAdminUseCase products;
    @Autowired ProductWebUseCase customerProducts;
    @Autowired SkuInternalUseCase internalSkus;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean ProductRepositoryPort repository;
    @MockitoSpyBean ProductOptionRepositoryPort options;
    TransactionTemplate tx;

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> {
            em.persist(ProductEntity.builder().id(ID).categoryId(1L).name("original").visible(false).saleStatus(SaleStatus.ON_SALE)
                .salePriceAmount(10000).salePriceCurrency(KRW).createdByMemberId(99L).build());
            em.persist(ProductOptionGroupEntity.builder().id(G1).productId(ID).name("first").displayOrder(0).build());
            em.persist(ProductOptionGroupEntity.builder().id(G2).productId(ID).name("second").displayOrder(1).retiredAt(Instant.parse("2026-01-01T00:00:00Z")).build());
            em.persist(ProductOptionValueEntity.builder().id(V1).productId(ID).optionGroupId(G1).name("a").displayOrder(0).build());
            em.persist(ProductOptionValueEntity.builder().id(V2).productId(ID).optionGroupId(G1).name("b").displayOrder(1).build());
            em.persist(ProductOptionValueEntity.builder().id(V3).productId(ID).optionGroupId(G2).name("__edit_0").displayOrder(0)
                .retiredAt(Instant.parse("2026-01-01T00:00:00Z")).build());
            em.persist(StockEntity.builder().skuId(SKU).stockManaged(true).availableQuantity(37).build());
            em.persist(StockReservationEntity.builder().id(ID + 50).orderId(ID + 60).activeOrderId(ID + 60)
                .status(StockReservationStatus.RESERVED).expiresAt(Instant.parse("2099-01-01T00:00:00Z")).build());
            em.persist(StockReservationItemEntity.builder().id(ID + 51).reservationId(ID + 50).skuId(SKU).quantity(3).deducted(true).build());
            em.persist(SkuCostEntity.builder().id(ID + 70).skuId(SKU).costType(SkuCostType.GOODS).amount(2000).currency(KRW)
                .effectiveFrom(Instant.parse("2026-01-01T00:00:00Z")).reason("seed").adminMemberId(99L).build());
            em.persist(SkuEntity.builder().id(SKU).productId(ID).optionValueKey(V1 + "," + V3).additionalPriceAmount(0)
                .additionalPriceCurrency(KRW).saleStatus(SaleStatus.ON_SALE).weightGrams(400).hsCode("123456").build());
        });
    }

    @AfterEach
    void cleanup() {
        reset(repository, options);
        tx.executeWithoutResult(s -> {
            for (var entity : List.of("SkuEntity", "ProductOptionValueEntity", "ProductOptionGroupEntity"))
                em.createQuery("delete from " + entity + " where productId = :id").setParameter("id", ID).executeUpdate();
            em.remove(em.find(StockEntity.class, SKU));
            em.remove(em.find(StockReservationItemEntity.class, ID + 51));
            em.remove(em.find(StockReservationEntity.class, ID + 50));
            em.createQuery("delete from SkuCostEntity where id = :id").setParameter("id", ID + 70).executeUpdate();
            em.createQuery("delete from ProductOptionValueEntity where productId = :id").setParameter("id", ID + 99).executeUpdate();
            em.createQuery("delete from ProductOptionGroupEntity where productId = :id").setParameter("id", ID + 99).executeUpdate();
            em.createQuery("delete from ProductEntity where id = :id").setParameter("id", ID + 99).executeUpdate();
            em.remove(em.find(ProductEntity.class, ID));
        });
    }

    @Test
    void swapsNamesOrderAndSkipsOccupiedTemporaryNamesPreservingMetadataAndSku() {
        var groupBefore = tx.execute(s -> em.find(ProductOptionGroupEntity.class, G2));
        var valueBefore = tx.execute(s -> em.find(ProductOptionValueEntity.class, V3));
        var skuBefore = tx.execute(s -> em.find(SkuEntity.class, SKU));
        var stockBefore = tx.execute(s -> em.find(StockEntity.class, SKU));
        var reservationBefore = tx.execute(s -> em.find(StockReservationEntity.class, ID + 50));
        var itemBefore = tx.execute(s -> em.find(StockReservationItemEntity.class, ID + 51));
        var costBefore = tx.execute(s -> em.find(SkuCostEntity.class, ID + 70));
        var response = products.updateOptions(changed());
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.optionGroups()).extracting(g -> g.optionGroupId()).containsExactly("" + G2, "" + G1);
        assertThat(response.optionGroups().get(1).values()).extracting(v -> v.name()).containsExactly("a", "b");
        tx.executeWithoutResult(s -> {
            var group = em.find(ProductOptionGroupEntity.class, G2);
            assertThat(group.getCreatedAt()).isEqualTo(groupBefore.getCreatedAt());
            assertThat(group.getRetiredAt()).isEqualTo(groupBefore.getRetiredAt());
            assertThat(group.getName()).isEqualTo("first");
            var value = em.find(ProductOptionValueEntity.class, V3);
            assertThat(value.getCreatedAt()).isEqualTo(valueBefore.getCreatedAt());
            assertThat(value.getRetiredAt()).isEqualTo(valueBefore.getRetiredAt());
            assertThat(value.getOptionGroupId()).isEqualTo(G2);
            assertThat(value.getName()).isEqualTo("__edit_1");
            assertThat(em.find(SkuEntity.class, SKU)).usingRecursiveComparison().isEqualTo(skuBefore);
            assertThat(em.find(StockEntity.class, SKU)).usingRecursiveComparison().isEqualTo(stockBefore);
            assertThat(em.find(StockReservationEntity.class, ID + 50)).usingRecursiveComparison().isEqualTo(reservationBefore);
            assertThat(em.find(StockReservationItemEntity.class, ID + 51)).usingRecursiveComparison().isEqualTo(itemBefore);
            assertThat(em.find(SkuCostEntity.class, ID + 70)).usingRecursiveComparison().isEqualTo(costBefore);
        });
    }

    @Test
    void fiftyCharacterExistingValueCanRoundTripAndBeEdited() {
        String longName = "n".repeat(50);
        tx.executeWithoutResult(s -> em.createQuery("update ProductOptionValueEntity set name = :name where id = :id")
            .setParameter("name", longName).setParameter("id", V1).executeUpdate());
        var first = new Group(G1, "first", List.of(new Value(V1, longName), new Value(V2, "b")));
        var request = new ProductOptionsUpdateCommand(ID, 0, 99, List.of(first, original(0).optionGroups().getLast()));
        assertThat(products.updateOptions(request).version()).isZero();
        var edited = new Group(G1, "first", List.of(new Value(V1, "e".repeat(50)), new Value(V2, "b")));
        assertThat(products.updateOptions(new ProductOptionsUpdateCommand(ID, 0, 99, List.of(edited, request.optionGroups().getLast()))).version()).isEqualTo(1);
    }

    @Test
    void retiredOptionsRemainAdminOnlyAndInternalUsesCurrentNames() {
        products.updateOptions(changed());
        assertThat(internalSkus.getSkus(List.of(SKU)).skus().getFirst().optionName()).isEqualTo("__edit_1 / b");
        tx.executeWithoutResult(s -> em.createQuery("update ProductEntity set visible = true where id = :id").setParameter("id", ID).executeUpdate());
        assertThat(customerProducts.getProduct(ID).optionGroups()).extracting(g -> g.optionGroupId()).containsExactly("" + G1);
        assertThat(customerProducts.getProduct(ID).skus()).isEmpty();
        assertThat(products.getProduct(ID).optionGroups()).hasSize(2);
    }

    @Test
    void identicalIsNoOpAndStaleFailsFirst() {
        var updated = tx.execute(s -> em.find(ProductEntity.class, ID).getUpdatedAt());
        assertThat(products.updateOptions(original(0)).version()).isZero();
        assertThat(tx.<Instant>execute(s -> em.find(ProductEntity.class, ID).getUpdatedAt())).isEqualTo(updated);
        error(() -> products.updateOptions(original(1)), "PRODUCT_010");
    }

    @Test
    void exactSetRejectsMissingDuplicateUnknownMovedIdsAndNormalizedDuplicateNames() {
        tx.executeWithoutResult(s -> {
            em.persist(ProductEntity.builder().id(ID + 99).categoryId(1L).name("foreign").visible(false).saleStatus(SaleStatus.ON_SALE)
                .salePriceAmount(10000).salePriceCurrency(KRW).createdByMemberId(99L).build());
            em.persist(ProductOptionGroupEntity.builder().id(G1 + 100).productId(ID + 99).name("foreign").displayOrder(0).build());
            em.persist(ProductOptionValueEntity.builder().id(V1 + 100).productId(ID + 99).optionGroupId(G1 + 100).name("foreign").displayOrder(0).build());
        });
        var first = original(0).optionGroups().getFirst();
        var second = original(0).optionGroups().getLast();
        for (var groups : List.of(List.<Group>of(), List.of(first), List.of(first, first),
            List.of(new Group(G1 + 100, "first", first.values()), second),
            List.of(new Group(G1, "first", List.of(new Value(V1 + 100, "x"), new Value(V2, "b"))), second),
            List.of(new Group(G1, "first", List.of(new Value(V1, "a"), new Value(V1, "b"))), second),
            List.of(new Group(G1, "first", List.of(new Value(V1, "a"), new Value(V3, "b"))), new Group(G2, "second", List.of(new Value(V2, "x")))),
            List.of(new Group(G1, "same", first.values()), new Group(G2, " same ", second.values())),
            List.of(new Group(G1, "first", List.of(new Value(V1, "a"), new Value(V2, " a "))), second)))
            error(() -> products.updateOptions(new ProductOptionsUpdateCommand(ID, 0, 99, groups)), "PRODUCT_013");
        assertThat(version()).isZero();
    }

    @Test
    void failureAfterFinalWritesRollsBackTemporaryAndFinalNamesAndVersion() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(em.find(ProductOptionGroupEntity.class, G1).getName()).isEqualTo("second");
            assertThat(em.find(ProductOptionValueEntity.class, V1).getName()).isEqualTo("b");
            throw new IllegalStateException("injected after final writes");
        }).when(options).updateLabels(anyList(), anyList(), anyList(), anyList());
        assertThatThrownBy(() -> products.updateOptions(changed())).isInstanceOf(IllegalStateException.class);
        assertThat(version()).isZero();
        assertThat(products.getProduct(ID).optionGroups()).extracting(g -> g.name()).containsExactly("first", "second");
        assertThat(products.getProduct(ID).optionGroups().getFirst().values()).extracting(v -> v.name()).containsExactly("a", "b");
    }

    @Test
    void emptyProductZeroValueGroupsAndMissingOrDeletedProduct() {
        tx.executeWithoutResult(s -> em.createQuery("delete from ProductOptionValueEntity where productId = :id").setParameter("id", ID).executeUpdate());
        var groups = List.of(new Group(G1, "first", List.of()), new Group(G2, "second", List.of()));
        assertThat(products.updateOptions(new ProductOptionsUpdateCommand(ID, 0, 99, groups)).version()).isZero();
        tx.executeWithoutResult(s -> em.createQuery("delete from ProductOptionGroupEntity where productId = :id").setParameter("id", ID).executeUpdate());
        assertThat(products.updateOptions(new ProductOptionsUpdateCommand(ID, 0, 99, List.of())).version()).isZero();
        error(() -> products.updateOptions(new ProductOptionsUpdateCommand(ID + 99, 0, 99, List.of())), "PRODUCT_001");
        products.delete(ID, 99);
        error(() -> products.updateOptions(new ProductOptionsUpdateCommand(ID, 0, 99, List.of())), "PRODUCT_001");
    }

    @Test
    void concurrentHeaderCommitMakesOptionCasFail() throws Exception {
        race(() -> products.updateHeader(new ProductHeaderUpdateCommand(ID, 0, 99, "winner", null, null, SaleStatus.STOP_SELLING, false)));
        assertThat(products.getProduct(ID).name()).isEqualTo("winner");
    }

    @Test
    void concurrentDeletionCannotBeRestored() throws Exception {
        race(() -> products.delete(ID, 99));
        assertThat(tx.<Boolean>execute(s -> repository.findAllByIdsIncludingHidden(List.of(ID)).getFirst().isDeleted())).isTrue();
    }

    private void race(Runnable other) throws Exception {
        var read = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        doAnswer(invocation -> { read.countDown(); await(committed); return invocation.callRealMethod(); }).when(repository).bumpVersion(ID, 0);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> {
                try { products.updateOptions(changed()); return "success"; }
                catch (ProductException e) { return e.getErrorCode().getCode(); }
            });
            try { await(read); other.run(); } finally { committed.countDown(); }
            assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo("PRODUCT_010");
        }
        assertThat(version()).isEqualTo(1);
        assertThat(tx.<String>execute(s -> em.find(ProductOptionGroupEntity.class, G1).getName())).isEqualTo("first");
    }
    private long version() { return tx.execute(s -> repository.findAllByIdsIncludingHidden(List.of(ID)).getFirst().version()); }
    private ProductOptionsUpdateCommand original(long version) {
        return new ProductOptionsUpdateCommand(ID, version, 99, List.of(new Group(G1, "first", List.of(new Value(V1, "a"), new Value(V2, "b"))),
            new Group(G2, "second", List.of(new Value(V3, "__edit_0")))));
    }
    private ProductOptionsUpdateCommand changed() {
        return new ProductOptionsUpdateCommand(ID, 0, 99, List.of(new Group(G2, "first", List.of(new Value(V3, "__edit_1"))),
            new Group(G1, "second", List.of(new Value(V2, "a"), new Value(V1, "b")))));
    }
    private static void error(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ProductException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo(code));
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
    }
}
