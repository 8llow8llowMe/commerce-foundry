package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request.ProductOptionsUpdateRequest;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request.ProductOptionsUpdateRequest.Group;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request.ProductOptionsUpdateRequest.Value;
import jakarta.validation.Validation;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ProductOptionsUpdateRequestTest {
    @Test
    void supportsRegistrationBoundsAndEmptyRoundTrips() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var values = IntStream.range(0, 50).mapToObj(i -> new Value((long) i + 1, "v".repeat(48) + String.format("%02d", i))).toList();
            var groups = IntStream.range(0, 5).mapToObj(i -> new Group((long) i + 1, "g".repeat(29) + i, values)).toList();
            var request = new ProductOptionsUpdateRequest(0L, groups);
            assertThat(validator.validate(request)).isEmpty();
            assertThat(request.toCommand(1, 2).optionGroups().getFirst().values()).hasSize(50);
            assertThat(validator.validate(new ProductOptionsUpdateRequest(0L, List.of()))).isEmpty();
            assertThat(validator.validate(new ProductOptionsUpdateRequest(0L, List.of(new Group(1L, "group", List.of()))))).isEmpty();
            assertThat(validator.validate(new ProductOptionsUpdateRequest(0L, IntStream.range(0, 6)
                .mapToObj(i -> new Group((long) i + 1, "g" + i, List.of())).toList())))
                .anyMatch(v -> v.getMessage().startsWith("PRODUCT_090:"));
            assertThat(validator.validate(new ProductOptionsUpdateRequest(0L, List.of(new Group(1L, "group", IntStream.range(0, 51)
                .mapToObj(i -> new Value((long) i + 1, "v" + i)).toList())))))
                .anyMatch(v -> v.getMessage().startsWith("PRODUCT_093:"));
        }
    }

    @Test
    void rejectsBlankAndControlOrFormatNames() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (String name : List.of(" ", "a\n", "a\u200b")) {
                var request = new ProductOptionsUpdateRequest(0L, List.of(new Group(1L, name, List.of(new Value(2L, name)))));
                assertThat(factory.getValidator().validate(request)).isNotEmpty();
            }
        }
    }
}
