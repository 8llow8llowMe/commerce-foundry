package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.AdminPasswordResetMailPort;
import com.commercefoundry.domainlayer.member.application.port.out.AdminPasswordResetTokenPort;
import com.commercefoundry.domainlayer.member.domain.model.AdminPasswordResetGrant;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.global.properties.AdminPasswordResetProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminPasswordResetProcessor {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AdminPasswordResetTokenPort tokenPort;
    private final AdminPasswordResetMailPort mailPort;
    private final AdminPasswordResetProperties properties;

    public void limitSend(long actor, long target) {
        requireEnabled();
        tokenPort.limit("send-global", 100, Duration.ofMinutes(15));
        tokenPort.limit("send-actor:" + actor, 10, Duration.ofMinutes(15));
        tokenPort.limit("send-target:" + target, 3, Duration.ofMinutes(15));
    }

    public void send(Member member) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String fingerprint = fingerprint(token);
        tokenPort.save(fingerprint, new AdminPasswordResetGrant(member.id(), member.version()), properties.tokenTtl());
        try { mailPort.send(member.email(), properties.resetUrl() + "#token=" + token); }
        catch (RuntimeException failure) { tokenPort.discard(fingerprint); throw failure; }
    }

    public AdminPasswordResetGrant consume(String token, String clientSubject) {
        requireEnabled();
        // 게이트웨이가 주입한 주체만 IP 차원으로 센다. 전체 공유 버킷은 익명 호출자의 서비스 거부 공격을 허용하므로 두지 않는다.
        // 헤더가 없거나 잘못되면 IP 제한을 건너뛴다. 아래 256비트 토큰 검사와 GETDEL을 통과해야 BCrypt를 실행한다.
        if (clientSubject != null && clientSubject.matches("[A-Za-z0-9_-]{22}")) {
            tokenPort.limit("use-client:" + clientSubject, 10, Duration.ofMinutes(1));
        }
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) { throw invalid(); }
        return tokenPort.consume(fingerprint(token)).orElseThrow(AdminPasswordResetProcessor::invalid);
    }

    public static String fingerprint(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private void requireEnabled() {
        if (!properties.enabled()) { throw new MemberException(MemberErrorCode.ADMIN_PASSWORD_RESET_UNAVAILABLE); }
    }

    private static MemberException invalid() { return new MemberException(MemberErrorCode.ADMIN_PASSWORD_RESET_INVALID); }
}
