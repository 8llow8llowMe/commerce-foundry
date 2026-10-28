package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.member.application.exception.*;
import com.commercefoundry.domainlayer.member.application.port.out.*;
import com.commercefoundry.domainlayer.member.domain.model.*;
import com.commercefoundry.global.properties.SocialLoginProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SocialAuthorizationProcessorTest {
    private final SocialProviderPort provider = mock(SocialProviderPort.class);
    private final SocialStateStorePort states = mock(SocialStateStorePort.class);
    private final SocialAuthorizationProcessor processor = new SocialAuthorizationProcessor(provider, states,
        new SocialLoginProperties(false, Duration.ofMinutes(5), Duration.ofSeconds(2), Duration.ofSeconds(3), null, null));

    @Test
    void opaqueIndependentSecretsAndHashesOnlyInStore() {
        when(states.save(anyString(), eq(SocialProvider.KAKAO), anyString(), eq(Duration.ofMinutes(5)))).thenReturn(true);
        when(provider.authorizeUrl(eq(SocialProvider.KAKAO), anyString())).thenAnswer(invocation -> "https://example.com?state=" + invocation.getArgument(1));
        var info = processor.authorize("kakao");
        assertThat(info.state()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(info.verifier());
        assertThat(info.authorizeUrl()).contains(info.state()).doesNotContain(info.verifier());
        verify(states).save(argThat(hash -> !hash.equals(info.state())), eq(SocialProvider.KAKAO),
            argThat(hash -> !hash.equals(info.verifier())), eq(Duration.ofMinutes(5)));
        assertThat(info.toString()).doesNotContain(info.state(), info.verifier());
    }

    @Test
    void mismatchExpiredOrReplayNeverCallsProvider() {
        assertThatThrownBy(() -> processor.authenticate("naver", "code", "a".repeat(43), "b".repeat(43)))
            .isInstanceOf(MemberException.class).extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_STATE_INVALID);
        verify(provider, never()).authenticate(any(), any(), any());
    }

    @Test
    void linkAuthorizationBindsJwtMemberAndHashesThenConsumesBeforeProvider() {
        when(states.saveLink(anyString(), eq(10L), eq(SocialProvider.KAKAO), anyString(), eq(Duration.ofMinutes(5)))).thenReturn(true);
        when(provider.authorizeUrl(eq(SocialProvider.KAKAO), anyString())).thenReturn("https://example.com");
        var info = processor.authorizeLink(10, "kakao");
        verify(states).saveLink(argThat(hash -> !hash.equals(info.state())), eq(10L), eq(SocialProvider.KAKAO),
            argThat(hash -> !hash.equals(info.verifier())), eq(Duration.ofMinutes(5)));
        assertThatThrownBy(() -> processor.authenticateLink(11, "kakao", "code", info.state(), info.verifier()))
            .isInstanceOf(MemberException.class).extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_STATE_INVALID);
        verify(provider, never()).authenticate(any(), any(), any());
        when(states.consumeLink(anyString(), eq(10L), eq(SocialProvider.KAKAO), anyString())).thenReturn(true);
        processor.authenticateLink(10, "kakao", "code", info.state(), info.verifier());
        var ordered = inOrder(states, provider);
        ordered.verify(states).consumeLink(anyString(), eq(10L), eq(SocialProvider.KAKAO), anyString());
        ordered.verify(provider).authenticate(SocialProvider.KAKAO, "code", info.state());
        verify(states, never()).consume(anyString(), any(), anyString());
    }

    @Test
    void malformedSecretDoesNotAccessStore() {
        assertThatThrownBy(() -> processor.authenticate("kakao", "code", "bad", "b".repeat(43))).isInstanceOf(MemberException.class);
        verifyNoInteractions(states);
    }

    @Test
    void supportedProviderAndConsumedStateAreRequiredBeforeExchange() {
        when(states.consume(anyString(), eq(SocialProvider.NAVER), anyString())).thenReturn(true);
        var profile = new SocialProfile(SocialProvider.NAVER, "subject", null, false, null);
        when(provider.authenticate(SocialProvider.NAVER, "code", "a".repeat(43))).thenReturn(profile);
        assertThat(processor.authenticate("naver", "code", "a".repeat(43), "b".repeat(43))).isEqualTo(profile);
        var order = inOrder(states, provider);
        order.verify(provider).requireEnabled(SocialProvider.NAVER);
        order.verify(states).consume(anyString(), eq(SocialProvider.NAVER), anyString());
        order.verify(provider).authenticate(SocialProvider.NAVER, "code", "a".repeat(43));
        assertThatThrownBy(() -> processor.authorize("unknown")).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.SOCIAL_PROVIDER_UNSUPPORTED);
    }
}
