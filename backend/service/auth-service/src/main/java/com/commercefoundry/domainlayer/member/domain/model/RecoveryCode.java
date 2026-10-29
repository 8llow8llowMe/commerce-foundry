package com.commercefoundry.domainlayer.member.domain.model;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 관리자 OTP 복구 코드 (#238) — 인증 앱을 잃었을 때 TOTP 대신 한 번 쓰는 코드. 저장은 정규화한 값의 BCrypt 해시만 한다.
 *
 * <ul>
 *   <li>알파벳은 헷갈리는 글자(I · O · 0 · 1)를 뺀 32자, 10자리(50비트). 보여 줄 때는 {@code xxxxx-xxxxx} 다</li>
 *   <li>입력은 대문자로 바꾸고 하이픈을 뺀다 — 사용자가 소문자 · 하이픈 없이 쳐도 같은 코드다</li>
 * </ul>
 */
public final class RecoveryCode {

    /** 한 번에 발급하는 개수. */
    public static final int COUNT = 8;
    public static final int LENGTH = 10;
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final int GROUP = 5;

    private RecoveryCode() {
    }

    /** 표시 형식({@code xxxxx-xxxxx}) 코드 하나. */
    public static String generate(SecureRandom random) {
        StringBuilder code = new StringBuilder(LENGTH + 1);
        for (int i = 0; i < LENGTH; i++) {
            if (i == GROUP) {
                code.append('-');
            }
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** 서로 다른 표시 형식 코드 {@code count} 개. */
    public static List<String> generate(SecureRandom random, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
        Set<String> codes = new LinkedHashSet<>();
        while (codes.size() < count) {
            codes.add(generate(random));
        }
        return List.copyOf(codes);
    }

    /**
     * 입력을 저장 · 비교 형식(대문자 10자, 하이픈 없음)으로 바꾼다. 앞뒤 공백을 자르고 대문자로 바꾸고 하이픈을 모두 뺀 뒤,
     * 길이 · 알파벳이 맞지 않으면 비어 있다 — 해시 비교(BCrypt) 전에 걸러 낸다.
     */
    public static Optional<String> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String normalized = input.strip().toUpperCase(Locale.ROOT).replace("-", "");
        if (normalized.length() != LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (ALPHABET.indexOf(normalized.charAt(i)) < 0) {
                return Optional.empty();
            }
        }
        return Optional.of(normalized);
    }
}
