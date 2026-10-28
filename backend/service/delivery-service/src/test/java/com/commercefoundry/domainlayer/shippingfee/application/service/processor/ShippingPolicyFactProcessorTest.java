package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.domainlayer.shippingfee.application.info.ShippingPolicyFactInfo;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyHistoryRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyRepositoryPort;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 운영자 온보딩의 "국내 배송비 정책 저장" 사실 (#357) — 스토어 준비와 운영자별 최초 저장을 따로 판정한다. */
@ExtendWith(MockitoExtension.class)
class ShippingPolicyFactProcessorTest {

    private static final long MEMBER_ID = 4611686018427387904L;

    @Mock
    private ShippingPolicyRepositoryPort shippingPolicyRepositoryPort;

    @Mock
    private ShippingPolicyHistoryRepositoryPort shippingPolicyHistoryRepositoryPort;

    @InjectMocks
    private ShippingPolicyFactProcessor processor;

    @Test
    @DisplayName("국내 정책이 없고 이력도 없으면 storeReady false · memberFirstSavedAt null 이다")
    void noPolicy() {
        given(shippingPolicyRepositoryPort.existsDomesticPolicy()).willReturn(false);
        given(shippingPolicyHistoryRepositoryPort.findDomesticFirstSavedAt(MEMBER_ID)).willReturn(Optional.empty());

        assertThat(processor.getFact(MEMBER_ID)).isEqualTo(new ShippingPolicyFactInfo(false, null));
    }

    @Test
    @DisplayName("정책은 있지만 그 운영자의 이력이 없으면(다른 운영자가 저장) storeReady true · memberFirstSavedAt null 이다")
    void savedByOtherMember() {
        given(shippingPolicyRepositoryPort.existsDomesticPolicy()).willReturn(true);
        given(shippingPolicyHistoryRepositoryPort.findDomesticFirstSavedAt(MEMBER_ID)).willReturn(Optional.empty());

        assertThat(processor.getFact(MEMBER_ID)).isEqualTo(new ShippingPolicyFactInfo(true, null));
    }

    @Test
    @DisplayName("그 운영자의 이력이 있으면 가장 이른 저장 시각을 그대로 낸다")
    void savedByMember() {
        Instant firstSavedAt = Instant.parse("2026-10-18T03:00:00Z");
        given(shippingPolicyRepositoryPort.existsDomesticPolicy()).willReturn(true);
        given(shippingPolicyHistoryRepositoryPort.findDomesticFirstSavedAt(MEMBER_ID)).willReturn(Optional.of(firstSavedAt));

        assertThat(processor.getFact(MEMBER_ID)).isEqualTo(new ShippingPolicyFactInfo(true, firstSavedAt));
    }
}
