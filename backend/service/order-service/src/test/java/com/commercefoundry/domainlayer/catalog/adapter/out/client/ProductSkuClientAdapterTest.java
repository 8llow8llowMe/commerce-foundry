package com.commercefoundry.domainlayer.catalog.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.catalog.adapter.out.client.feign.ProductSkuClient;
import com.commercefoundry.domainlayer.catalog.adapter.out.client.feign.dto.ProductSkuInternalItem;
import com.commercefoundry.domainlayer.catalog.adapter.out.client.feign.dto.ProductSkuInternalResponse;
import com.commercefoundry.domainlayer.catalog.adapter.out.client.support.ProductInternalResponseSupport;
import com.commercefoundry.domainlayer.catalog.application.port.out.query.SkuQueryResult;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductSkuClientAdapterTest {

    private ProductSkuClient client;
    private ProductSkuClientAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(ProductSkuClient.class);
        adapter = new ProductSkuClientAdapter(client, new ProductInternalResponseSupport(CircuitBreakerRegistry.ofDefaults()));
    }

    private static ProductSkuInternalItem item(long skuId) {
        return new ProductSkuInternalItem(String.valueOf(skuId), "7301", "저고리", "", MoneyItem.of(39_000, "KRW"),
            CodeNameDescriptionMetadata.of("ON_SALE", "판매중", "구매할 수 있습니다."), true, null, false, null, null);
    }

    /** 흉내 낸 product-service — 받은 아이디를 그대로 돌려준다. */
    private void echoSkus() {
        given(client.getSkus(anyList())).willAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return Response.success(new ProductSkuInternalResponse(ids.stream().map(ProductSkuClientAdapterTest::item).toList()));
        });
    }

    @Test
    @DisplayName("빈 아이디 목록이면 product-service 를 부르지 않는다 — 대상은 빈 ids 를 400 으로 거절한다")
    void emptyIdsDoNotCall() {
        assertThat(adapter.findSkus(List.of())).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("중복을 지우고 한 번에 부른다")
    void distinctSingleCall() {
        given(client.getSkus(List.of(9004L, 9005L))).willReturn(Response.success(new ProductSkuInternalResponse(List.of(item(9004L)))));

        List<SkuQueryResult> results = adapter.findSkus(List.of(9004L, 9005L, 9004L));

        verify(client).getSkus(List.of(9004L, 9005L));
        assertThat(results).singleElement().extracting(SkuQueryResult::skuId).isEqualTo(9004L);
    }

    @Test
    @DisplayName("상한(100개)을 넘으면 100개씩 나눠 부르고 결과를 요청 순서로 잇는다 — 50줄 상한이 동시 요청으로 넘어도 조회가 막히지 않는다")
    void chunks() {
        echoSkus();
        List<Long> ids = LongStream.rangeClosed(1, ProductSkuClient.MAX_IDS_PER_CALL * 2L + 1).boxed().toList();

        List<SkuQueryResult> results = adapter.findSkus(ids);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> calls = ArgumentCaptor.forClass(List.class);
        verify(client, times(3)).getSkus(calls.capture());
        assertThat(calls.getAllValues()).extracting(List::size).containsExactly(100, 100, 1);
        assertThat(results).extracting(SkuQueryResult::skuId).containsExactlyElementsOf(ids);
    }

    @Test
    @DisplayName("정확히 100개면 한 번에 부른다")
    void exactlyMaxIsSingleCall() {
        echoSkus();

        adapter.findSkus(LongStream.rangeClosed(1, ProductSkuClient.MAX_IDS_PER_CALL).boxed().toList());

        verify(client, times(1)).getSkus(anyList());
    }
}
