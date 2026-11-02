package com.commercefoundry.domainlayer.aiusage.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiPriceTableTest {
    private static AiPrice price(String input, String output) {
        return new AiPrice(new BigDecimal(input), new BigDecimal(output), BigDecimal.ZERO, BigDecimal.ZERO);
    }
    @Test void namespaceExactThenFlatExactAndIsolatedMaxFallback() {
        AiPrice flat = price("20", "2"), openai = price("2", "10"), anthropic = price("1000", "1000");
        AiPriceTable table = new AiPriceTable("v1", Map.of("alias", flat, "flat-only", price("3", "4")),
            Map.of("openai", Map.of("alias", openai), "anthropic", Map.of("alias", anthropic)));
        assertThat(table.quote("openai", "alias").price()).isEqualTo(openai);
        assertThat(table.quote("anthropic", "alias").price()).isEqualTo(anthropic);
        assertThat(table.quote("openai", "flat-only").price()).isEqualTo(price("3", "4"));
        assertThat(table.quote("openai", "missing").pricedBy()).isEqualTo(AiPricedBy.MAX_FALLBACK);
        assertThat(table.quote("openai", "missing").price()).isEqualTo(price("20", "10"));
        assertThat(table.quote(null, "alias").price()).isEqualTo(flat);
        assertThat(table.quote("alias").price()).isEqualTo(flat);
        assertThat(table.quote(null, "missing").price()).isEqualTo(price("20", "4"));
        assertThat(table.hasExplicitPrice("openai", "alias")).isTrue();
        assertThat(table.hasExplicitPrice("openai", "missing")).isFalse();
    }
    @Test void providerOnlyPricingDoesNotLeakIntoLegacyOrOtherProviders() {
        AiPriceTable table = new AiPriceTable("v1", Map.of(), Map.of("openai", Map.of("alias", price("2", "10"))));
        assertThat(table.isEmpty()).isFalse();
        assertThat(table.hasPricePool("openai")).isTrue();
        assertThat(table.hasPricePool("anthropic")).isFalse();
        assertThat(table.hasPricePool(null)).isFalse();
        assertThatThrownBy(() -> table.quote(null, "alias")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> table.quote("anthropic", "alias")).isInstanceOf(IllegalStateException.class);
    }
}
