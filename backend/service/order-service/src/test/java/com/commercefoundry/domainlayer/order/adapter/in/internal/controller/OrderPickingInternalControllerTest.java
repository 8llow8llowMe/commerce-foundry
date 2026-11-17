package com.commercefoundry.domainlayer.order.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPickingInternalResponse;
import com.commercefoundry.domainlayer.order.application.port.in.OrderPickingInternalUseCase;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(OrderPickingInternalController.class)
@Import({InternalResourceServerConfigurer.class, InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class, ResourceServerSecurityConfigurer.class})
class OrderPickingInternalControllerTest {
    @Autowired private MockMvcTester mvc;
    @MockitoBean private OrderPickingInternalUseCase useCase;

    @Test
    void deliveryCanReadBulkAndIdentifiersRemainStrings() {
        var response = new OrderPickingInternalResponse("4611686018427387904", "PREPARING", "KR",
            List.of(new OrderPickingInternalResponse.Line("7010", "10", 2, false)));
        given(useCase.findAll(List.of(1L, 2L))).willReturn(List.of(response));
        var bulk = mvc.get().uri("/internal/v1/orders/picking-snapshots?orderIds=1,2")
            .header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer("delivery-service", "order-service")).exchange();
        assertThat(bulk).hasStatusOk();
        assertThat(bulk).bodyJson().extractingPath("$.dataBody[0].orderId").isEqualTo("4611686018427387904");
        assertThat(bulk).bodyJson().extractingPath("$.dataBody[0].lines[0].productId").isEqualTo("7010");
    }

    @Test
    void otherServiceAndAnonymousCannotRead() {
        String path = "/internal/v1/orders/picking-snapshots?orderIds=1";
        assertThat(mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION,
            TestServiceTokens.bearer("payment-service", "order-service")).exchange()).hasStatus(403);
        assertThat(mvc.get().uri(path).exchange()).hasStatus(401);
        verifyNoInteractions(useCase);
    }
}
