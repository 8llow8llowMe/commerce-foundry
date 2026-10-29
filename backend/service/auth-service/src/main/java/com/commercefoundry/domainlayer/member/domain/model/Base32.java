package com.commercefoundry.domainlayer.member.domain.model;

/**
 * RFC 4648 §6 Base32 — 관리자 OTP 비밀을 인증 앱에 넘기는 형식(otpauth URI 의 {@code secret}, #238).
 *
 * <p>인코딩은 대문자 · 패딩 없음 하나다. 디코딩도 그 모양만 받는다 — 소문자 · 패딩({@code =}) · 공백 · 다른 문자, 끝 자리가 남는 길이(8로 나눈 나머지 1 · 3 · 6),
 * 남는 비트가 0 이 아닌 입력은 {@link IllegalArgumentException} 이다. 같은 바이트가 여러 문자열로 표현되지 않게 한다.
 */
public final class Base32 {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int[] LOOKUP = new int[128];

    static {
        java.util.Arrays.fill(LOOKUP, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            LOOKUP[ALPHABET[i]] = i;
        }
    }

    private Base32() {
    }

    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET[(buffer >>> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer << (5 - bits)) & 0x1f]);
        }
        return out.toString();
    }

    public static byte[] decode(String text) {
        if (text == null) {
            throw new IllegalArgumentException("base32 text must not be null");
        }
        int remainder = text.length() % 8;
        if (remainder == 1 || remainder == 3 || remainder == 6) {
            throw new IllegalArgumentException("base32 length is not valid");
        }
        byte[] out = new byte[text.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int value = c < LOOKUP.length ? LOOKUP[c] : -1;
            if (value < 0) {
                // 입력 문자는 메시지에 넣지 않는다 — 비밀일 수 있다
                throw new IllegalArgumentException("base32 text has a character outside the upper-case alphabet");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >>> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        if ((buffer & ((1 << bits) - 1)) != 0) {
            throw new IllegalArgumentException("base32 trailing bits must be zero");
        }
        return out;
    }
}
