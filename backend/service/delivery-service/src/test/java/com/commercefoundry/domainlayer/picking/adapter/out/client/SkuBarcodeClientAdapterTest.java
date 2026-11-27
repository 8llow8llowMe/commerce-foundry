package com.commercefoundry.domainlayer.picking.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.picking.adapter.out.client.feign.SkuBarcodeClient;
import com.commercefoundry.domainlayer.picking.adapter.out.client.support.SkuBarcodeInternalResponseSupport;
import com.commercefoundry.domainlayer.picking.application.exception.PickingErrorCode;
import com.commercefoundry.domainlayer.picking.application.exception.PickingException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkuBarcodeClientAdapterTest {

    private final SkuBarcodeClient client = mock(SkuBarcodeClient.class);
    private final SkuBarcodeClientAdapter adapter = new SkuBarcodeClientAdapter(
        client, new SkuBarcodeInternalResponseSupport(CircuitBreakerRegistry.ofDefaults())
    );

    @Test
    void readsOneBulkEnvelopeAndLeavesMissingBarcodesUnresolved() {
        Set<String> requested = Set.of("0001", "unknown");
        var request = new SkuBarcodeClient.Search(requested);
        given(client.resolve(request)).willReturn(Response.success(new SkuBarcodeClient.Resolved(Map.of("0001", "9223372036854775807"))));

        assertThat(adapter.resolve(requested)).containsExactlyEntriesOf(Map.of("0001", Long.MAX_VALUE));
        verify(client).resolve(request);
    }

    @Test
    void rejectsUnexpectedKeysInvalidIdsAndMissingEnvelopesAsLookupFailures() {
        Set<String> requested = Set.of("0001");
        var request = new SkuBarcodeClient.Search(requested);
        given(client.resolve(request))
            .willReturn(Response.success(new SkuBarcodeClient.Resolved(Map.of("unrequested", "10"))))
            .willReturn(Response.success(new SkuBarcodeClient.Resolved(Map.of("0001", "9223372036854775808"))))
            .willReturn(null);

        for (int call = 0; call < 3; call++) {
            assertThatThrownBy(() -> adapter.resolve(requested)).isInstanceOfSatisfying(PickingException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(PickingErrorCode.BARCODE_LOOKUP_UNAVAILABLE));
        }
    }
}
