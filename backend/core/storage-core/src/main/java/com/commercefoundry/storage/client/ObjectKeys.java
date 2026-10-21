package com.commercefoundry.storage.client;

import java.nio.charset.StandardCharsets;

/** 키 검사와 공개 URL 조립. 두 구현(S3 · 꺼짐)이 같이 쓴다. */
final class ObjectKeys {

    /** S3 키 상한(UTF-8 바이트). */
    static final int MAX_KEY_BYTES = 1024;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private ObjectKeys() {
    }

    /**
     * 비었거나 {@code /} 로 시작하거나 빈 · {@code .} · {@code ..} 구간이 있거나 제어 문자가 있거나 1024바이트를 넘으면 거절한다.
     * {@code ..} 은 S3 에서는 그냥 글자지만 공개 URL 로 이으면 브라우저 · CDN 이 경로를 정규화해 다른 객체를 가리킨다.
     */
    static String requireValid(String key) {
        if (key == null || key.isEmpty() || key.charAt(0) == '/') {
            throw new IllegalArgumentException("스토리지 키가 비었거나 '/' 로 시작합니다");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("스토리지 키가 " + MAX_KEY_BYTES + "바이트를 넘습니다");
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i))) {
                throw new IllegalArgumentException("스토리지 키에 제어 문자가 있습니다");
            }
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("스토리지 키에 빈 구간 · '.' · '..' 이 있습니다");
            }
        }
        return key;
    }

    static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 가 비었습니다");
        }
    }

    /** {@code {publicBaseUrl}/{인코딩한 키}}. {@code publicBaseUrl} 은 끝 {@code /} 를 지운 값이다({@code StorageProperties}). */
    static String publicUrl(String publicBaseUrl, String key) {
        return publicBaseUrl + "/" + encodePath(requireValid(key));
    }

    /** 구간마다 RFC 3986 unreserved({@code A-Z a-z 0-9 - . _ ~}) 밖의 바이트를 {@code %XX} 로 바꾼다. 구분자 {@code /} 는 둔다. */
    static String encodePath(String key) {
        StringBuilder encoded = new StringBuilder(key.length() * 3);
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c == '/' || isUnreserved(c)) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return encoded.toString();
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
    }
}
