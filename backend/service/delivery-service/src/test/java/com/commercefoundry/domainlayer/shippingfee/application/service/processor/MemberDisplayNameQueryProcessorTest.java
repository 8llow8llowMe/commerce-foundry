package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.shippingfee.application.port.out.MemberDisplayNameQueryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory.Snapshot;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

@ExtendWith(MockitoExtension.class)
class MemberDisplayNameQueryProcessorTest {

    @Mock
    private MemberDisplayNameQueryPort memberDisplayNameQueryPort;

    @InjectMocks
    private MemberDisplayNameQueryProcessor processor;

    @Test
    @DisplayName("이력 항목이 여러 개여도 표시 이름 조회는 한 번이고, 관리자 아이디 중복은 뺀다")
    void looksUpOnce() {
        Snapshot after = new Snapshot(krw(3_000), null, List.of(), 0L);
        Instant changedAt = Instant.parse("2026-10-18T03:00:00Z");
        Page<ShippingPolicyHistory> histories = new PageImpl<>(List.of(
            new ShippingPolicyHistory(1L, 7001L, ShippingScope.DOMESTIC, 11L, null, after, changedAt),
            new ShippingPolicyHistory(2L, 7001L, ShippingScope.DOMESTIC, 22L, null, after, changedAt),
            new ShippingPolicyHistory(3L, 7001L, ShippingScope.DOMESTIC, 11L, null, after, changedAt)
        ));
        given(memberDisplayNameQueryPort.findMaskedDisplayNames(List.of(11L, 22L))).willReturn(Map.of(11L, "홍*동"));

        assertThat(processor.findMaskedNames(histories)).containsEntry(11L, "홍*동");
        verify(memberDisplayNameQueryPort, times(1)).findMaskedDisplayNames(List.of(11L, 22L));
    }
}
