package com.commercefoundry.domainlayer.shippingfee.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.client.feign.MemberDisplayNameClient;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.client.feign.dto.MemberDisplayNameInternalResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.client.support.MemberDisplayNameInternalResponseSupport;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberDisplayNameClientAdapterTest {

    @Mock
    private MemberDisplayNameClient memberDisplayNameClient;

    @Mock
    private MemberDisplayNameInternalResponseSupport memberDisplayNameInternalResponseSupport;

    @InjectMocks
    private MemberDisplayNameClientAdapter adapter;

    @Test
    @DisplayName("아이디가 없으면 Feign 을 부르지 않는다")
    void emptySkipsClient() {
        assertThat(adapter.findMaskedDisplayNames(List.of())).isEmpty();
        verifyNoInteractions(memberDisplayNameClient, memberDisplayNameInternalResponseSupport);
    }

    @Test
    @DisplayName("아이디가 여러 개여도 Feign 호출은 한 번이고 중복은 뺀다")
    void callsOnce() {
        Response<MemberDisplayNameInternalResponse> response = Response.success(new MemberDisplayNameInternalResponse(List.of()));
        given(memberDisplayNameInternalResponseSupport.call(any())).willAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        given(memberDisplayNameClient.getDisplayNames(List.of(11L, 22L))).willReturn(response);
        given(memberDisplayNameInternalResponseSupport.toMaskedNames(response)).willReturn(Map.of(11L, "홍*동"));

        assertThat(adapter.findMaskedDisplayNames(List.of(11L, 22L, 11L))).containsEntry(11L, "홍*동");
        verify(memberDisplayNameClient, times(1)).getDisplayNames(List.of(11L, 22L));
    }
}
