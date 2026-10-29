package com.commercefoundry.domainlayer.order.application.command;

import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.request.ShippingAddressRequest;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.request.OrderCreateRequest;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.ShippingAddressItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderCreateResponse;
import com.commercefoundry.domainlayer.order.domain.model.ShippingAddress;
import com.commercefoundry.domainlayer.order.support.OrderFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderCreateCommandTest {
    private OrderCreateCommand command(ShippingAddress address) {
        return new OrderCreateCommand(List.of(OrderFixtures.line(1, 1)), address, null);
    }

    @Test
    void everyAddressFieldChangesDigestAndNormalizationIsStable() {
        var fields = new String[] {"recipient", "01000000001", "KR", "63100", "street", "detail", "city", "state", "memo"};
        var base = command(address(fields));
        for (int i = 0; i < fields.length; i++) {
            var changed = fields.clone();
            changed[i] = switch (i) { case 1 -> "01000000002"; case 2 -> "US"; case 3 -> "63101"; default -> fields[i] + "x"; };
            assertThat(command(address(changed)).fingerprint()).as("field %s", i).isNotEqualTo(base.fingerprint());
        }
        var dto = new ShippingAddressRequest(" recipient ", "010-0000-0001", " KR ", " 63100 ", " street ", " detail ", " memo ", " city ", " state ");
        assertThat(command(dto.toShippingAddress()).fingerprint()).isEqualTo(base.fingerprint());
        assertThat(base.fingerprint()).matches("[a-f0-9]{64}");
        assertThat(new OrderCreateCommand(base.items(), base.shippingAddress(), OrderFixtures.krw(1)).fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(new OrderCreateCommand(List.of(OrderFixtures.line(1, 2)), base.shippingAddress(), null).fingerprint()).isNotEqualTo(base.fingerprint());
    }

    private ShippingAddress address(String[] f) { return new ShippingAddress(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8]); }

    @Test
    void newEntryAndResponseStringsMaskInput() {
        var marker = "PRIVATE_MARKER";
        var dto = new ShippingAddressRequest(marker, "01000000001", "KR", "63100", marker, marker, marker, marker, marker);
        assertThat(dto.toString()).doesNotContain(marker, "01000000001", "63100");
        assertThat(command(dto.toShippingAddress()).toString()).doesNotContain(marker, "01000000001", "63100");
        assertThat(new OrderCreateRequest(null, dto, null).toString()).doesNotContain(marker);
        assertThat(ShippingAddressItem.builder().recipientName(marker).build().toString()).doesNotContain(marker);
        assertThat(OrderCreateResponse.builder().orderId(marker).build().toString()).doesNotContain(marker);
    }
}
