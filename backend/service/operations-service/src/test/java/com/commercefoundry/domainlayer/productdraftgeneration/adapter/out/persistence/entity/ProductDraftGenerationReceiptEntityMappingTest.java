package com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationReceipt;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProductDraftGenerationReceiptEntityMappingTest {
    private static final String HASH = "a".repeat(64);
    private static final Instant EXPIRES_AT = Instant.parse("2026-11-20T00:00:00Z");

    @Test
    void receiptUsesDefaultSchemaWithoutExplicitDuplicateSchemaDeclaration() {
        Table table = ProductDraftGenerationReceiptEntity.class.getAnnotation(Table.class);
        assertThat(table.name()).isEqualTo("product_draft_generation_receipt");
        assertThat(table.schema()).isEmpty();
    }

    @Test
    void receiptIsKeyedByDraftAndGenerationNumber() {
        IdClass idClass = ProductDraftGenerationReceiptEntity.class.getAnnotation(IdClass.class);
        assertThat(idClass.value()).isEqualTo(ProductDraftGenerationReceiptEntity.Key.class);
        assertThat(new ProductDraftGenerationReceiptEntity.Key(1L, 2)).isEqualTo(new ProductDraftGenerationReceiptEntity.Key(1L, 2))
            .isNotEqualTo(new ProductDraftGenerationReceiptEntity.Key(1L, 3));
    }

    @Test
    void entityRoundTripsDomainReceipt() {
        ProductDraftGenerationReceipt receipt = new ProductDraftGenerationReceipt(10L, 3, 20L, HASH, 30L, EXPIRES_AT);

        assertThat(new ProductDraftGenerationReceiptEntity(receipt).toDomain()).isEqualTo(receipt);
    }

    @Test
    void receiptRejectsGenerationNumberOutsideOneToThree() {
        assertThatThrownBy(() -> new ProductDraftGenerationReceipt(10L, 0, 20L, HASH, 30L, EXPIRES_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductDraftGenerationReceipt(10L, 4, 20L, HASH, 30L, EXPIRES_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductDraftGenerationReceipt(10L, 1, 20L, "A".repeat(64), 30L, EXPIRES_AT))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
