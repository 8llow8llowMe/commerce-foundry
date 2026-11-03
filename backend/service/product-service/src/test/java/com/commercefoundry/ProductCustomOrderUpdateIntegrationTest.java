package com.commercefoundry;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductCustomOrderFieldEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.application.command.ProductCustomOrderCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductCustomOrderCommand.Field;
import com.commercefoundry.domainlayer.product.application.command.ProductCustomOrderUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.application.port.in.SkuInternalUseCase;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 맞춤 제작 설정 전체 교체 (#9 PR-B) — 같은 키는 아이디 유지, 빠진 키 삭제, 새 키 추가, 주문이 읽는 내부 SKU 정의까지. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:product-custom-order-edit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductCustomOrderUpdateIntegrationTest {

    static final long ID = 880001, CHEST = 880011, MEMO = 880012, SKU = 880031;

    @Autowired EntityManager em;
    @Autowired ProductAdminUseCase products;
    @Autowired SkuInternalUseCase internalSkus;
    @Autowired PlatformTransactionManager transactionManager;
    TransactionTemplate tx;

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> {
            em.persist(ProductEntity.builder().id(ID).categoryId(1L).name("저고리").visible(true).saleStatus(SaleStatus.ON_SALE)
                .salePriceAmount(10000).salePriceCurrency(KRW).customOrder(true).customOrderNotice("2주 걸립니다").customOrderLeadTimeDays(14)
                .createdByMemberId(99L).build());
            em.persist(ProductCustomOrderFieldEntity.builder().id(CHEST).productId(ID).fieldKey("chest").label("가슴둘레")
                .inputType(CustomOrderInputType.NUMBER).unit("cm").required(true).minValue(new BigDecimal("60.00"))
                .maxValue(new BigDecimal("150.00")).stepValue(new BigDecimal("0.50")).displayOrder(0).build());
            em.persist(ProductCustomOrderFieldEntity.builder().id(MEMO).productId(ID).fieldKey("memo").label("요청 사항")
                .inputType(CustomOrderInputType.TEXT).required(false).maxValue(new BigDecimal("200")).displayOrder(1).build());
            em.persist(SkuEntity.builder().id(SKU).productId(ID).optionValueKey("").additionalPriceAmount(0)
                .additionalPriceCurrency(KRW).saleStatus(SaleStatus.ON_SALE).build());
        });
    }

    @AfterEach
    void cleanup() {
        tx.executeWithoutResult(s -> {
            em.createQuery("delete from ProductCustomOrderFieldEntity where productId = :id").setParameter("id", ID).executeUpdate();
            em.createQuery("delete from SkuEntity where productId = :id").setParameter("id", ID).executeUpdate();
            em.createQuery("delete from ProductEntity where id = :id").setParameter("id", ID).executeUpdate();
        });
    }

    private static Field chest(String label, String max) {
        return new Field("chest", label, CustomOrderInputType.NUMBER, "cm", true, new BigDecimal("60"), new BigDecimal(max), new BigDecimal("0.5"), null);
    }

    private static Field memo() {
        return new Field("memo", "요청 사항", CustomOrderInputType.TEXT, null, false, null, new BigDecimal("200"), null, null);
    }

    private static ProductCustomOrderUpdateCommand command(long version, boolean enabled, String notice, Integer leadTime, List<Field> fields) {
        return new ProductCustomOrderUpdateCommand(ID, version, 99, new ProductCustomOrderCommand(enabled, notice, leadTime, fields));
    }

    @Test
    @DisplayName("같은 키는 아이디를 유지해 고치고, 빠진 키는 지우고, 새 키는 더한다 — 상품 칸이 그대로여도 버전이 오르고 주문이 읽는 정의가 바뀐다")
    void replacesFieldsByKey() {
        var sleeve = new Field("sleeve", "소매 길이", CustomOrderInputType.NUMBER, "cm", false, null, null, null, "어깨 끝부터");

        var response = products.updateCustomOrder(command(0, true, "2주 걸립니다", 14, List.of(sleeve, chest("가슴 둘레", "160"))));

        assertThat(response.version()).isEqualTo(1);
        assertThat(response.customOrderFields()).extracting(f -> f.fieldKey()).containsExactly("sleeve", "chest");
        tx.executeWithoutResult(s -> {
            var rows = em.createQuery("from ProductCustomOrderFieldEntity where productId = :id order by displayOrder", ProductCustomOrderFieldEntity.class)
                .setParameter("id", ID).getResultList();
            assertThat(rows).extracting(ProductCustomOrderFieldEntity::getFieldKey).containsExactly("sleeve", "chest");
            assertThat(rows.get(1).getId()).isEqualTo(CHEST);
            assertThat(rows.get(1).getLabel()).isEqualTo("가슴 둘레");
            assertThat(rows.get(1).getMaxValue()).isEqualByComparingTo("160");
            assertThat(em.find(ProductCustomOrderFieldEntity.class, MEMO)).isNull();
        });
        var internal = internalSkus.getSkus(List.of(SKU)).skus().getFirst();
        assertThat(internal.customOrderFields()).extracting(f -> f.fieldKey()).containsExactly("sleeve", "chest");
    }

    @Test
    @DisplayName("바뀐 것이 없으면(60 과 60.00 처럼 크기가 같은 숫자 포함) 아무것도 쓰지 않고 버전을 유지한다")
    void unchangedKeepsVersion() {
        var response = products.updateCustomOrder(command(0, true, "2주 걸립니다", 14, List.of(chest("가슴둘레", "150"), memo())));

        assertThat(response.version()).isZero();
    }

    @Test
    @DisplayName("맞춤 제작을 끄면 항목은 남고, 주문이 읽는 정의는 빈 목록이 된다")
    void disablingKeepsFields() {
        var response = products.updateCustomOrder(command(0, false, null, null, List.of(chest("가슴둘레", "150"), memo())));

        assertThat(response.customOrder()).isFalse();
        assertThat(response.customOrderNotice()).isNull();
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.customOrderFields()).hasSize(2);
        assertThat(internalSkus.getSkus(List.of(SKU)).skus().getFirst().customOrderFields()).isEmpty();
    }

    @Test
    @DisplayName("오래된 버전은 409 PRODUCT_010 이고 항목을 쓰지 않는다")
    void staleVersionWritesNothing() {
        assertThatThrownBy(() -> products.updateCustomOrder(command(5, true, null, null, List.of())))
            .isInstanceOfSatisfying(ProductException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT));
        tx.executeWithoutResult(s -> assertThat(em.find(ProductCustomOrderFieldEntity.class, MEMO)).isNotNull());
    }
}
