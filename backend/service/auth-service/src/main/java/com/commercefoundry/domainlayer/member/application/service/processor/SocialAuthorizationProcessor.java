package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.info.SocialAuthorizationInfo;
import com.commercefoundry.domainlayer.member.application.port.out.SocialProviderPort;
import com.commercefoundry.domainlayer.member.application.port.out.SocialStateStorePort;
import com.commercefoundry.domainlayer.member.domain.model.SocialProfile;
import com.commercefoundry.domainlayer.member.domain.model.SocialProvider;
import com.commercefoundry.global.properties.SocialLoginProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SocialAuthorizationProcessor {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SocialProviderPort providerPort;
    private final SocialStateStorePort stateStore;
    private final SocialLoginProperties properties;

    public SocialAuthorizationInfo authorize(String providerName) {
        SocialProvider provider = provider(providerName);
        providerPort.requireEnabled(provider);
        for (int attempt = 0; attempt < 3; attempt++) {
            String state = randomToken();
            String verifier = randomToken();
            if (stateStore.save(hash(state), provider, hash(verifier), properties.stateTtl())) {
                return new SocialAuthorizationInfo(providerPort.authorizeUrl(provider, state), state, verifier);
            }
        }
        throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
    }

    public SocialProfile authenticate(String providerName, String code, String state, String verifier) {
        SocialProvider provider = provider(providerName);
        providerPort.requireEnabled(provider);
        if (!tokenShape(state) || !tokenShape(verifier) || !stateStore.consume(hash(state), provider, hash(verifier))) {
            throw new MemberException(MemberErrorCode.SOCIAL_STATE_INVALID);
        }
        return providerPort.authenticate(provider, code, state);
    }

    public SocialAuthorizationInfo authorizeLink(long memberId, String providerName) {
        SocialProvider provider = provider(providerName);
        providerPort.requireEnabled(provider);
        for (int attempt = 0; attempt < 3; attempt++) {
            String state = randomToken();
            String verifier = randomToken();
            if (stateStore.saveLink(hash(state), memberId, provider, hash(verifier), properties.stateTtl())) {
                return new SocialAuthorizationInfo(providerPort.authorizeUrl(provider, state), state, verifier);
            }
        }
        throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
    }

    public SocialProfile authenticateLink(long memberId, String providerName, String code, String state, String verifier) {
        SocialProvider provider = provider(providerName);
        providerPort.requireEnabled(provider);
        if (!tokenShape(state) || !tokenShape(verifier) || !stateStore.consumeLink(hash(state), memberId, provider, hash(verifier))) {
            throw new MemberException(MemberErrorCode.SOCIAL_STATE_INVALID);
        }
        return providerPort.authenticate(provider, code, state);
    }

    private SocialProvider provider(String value) {
        try {
            return SocialProvider.parse(value);
        } catch (IllegalArgumentException invalid) {
            throw new MemberException(MemberErrorCode.SOCIAL_PROVIDER_UNSUPPORTED);
        }
    }

    private static boolean tokenShape(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{43}");
    }

    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}