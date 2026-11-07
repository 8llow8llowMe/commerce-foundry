package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

class SizeChartGenerationReceiptEntityMappingTest {
    @Test
    void receiptUsesDefaultSchemaWithoutExplicitDuplicateSchemaDeclaration() {
        Table table = SizeChartGenerationReceiptEntity.class.getAnnotation(Table.class);
        assertThat(table.name()).isEqualTo("size_chart_generation_receipt");
        assertThat(table.schema()).isEmpty();
    }
}
