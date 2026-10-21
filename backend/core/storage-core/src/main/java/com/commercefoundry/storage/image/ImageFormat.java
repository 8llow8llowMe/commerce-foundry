package com.commercefoundry.storage.image;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 허용하는 이미지 형식과 판별 규칙. 확장자 · Content-Type 은 위조할 수 있으므로 저장 전 객체 앞부분(magic bytes)으로 다시 판별한다.
 *
 * <p>판별은 파일 <b>맨 앞</b>만 본다 — 앞이 다른 형식이고 뒤에 JPEG 를 붙인 폴리글롯, SVG · HTML(스크립트를 품을 수 있다) · GIF 는 어느 형식도 아니다.
 * 앞 {@link #DETECTION_LENGTH} 바이트를 {@code ObjectStorageClient.readRange} 로 읽어 {@link #detect} 에 넘긴다.
 */
public enum ImageFormat {

    /** {@code FF D8 FF} */
    JPEG("jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    /** PNG 시그니처 8바이트 {@code 89 50 4E 47 0D 0A 1A 0A} */
    PNG("png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}),
    /** {@code RIFF} + 크기 4바이트 + {@code WEBP} (12바이트) */
    WEBP("webp", "image/webp", new byte[] {'R', 'I', 'F', 'F'});

    /** 판별에 필요한 최대 길이 — WebP 의 {@code RIFF????WEBP}. 이만큼 읽어 {@link #detect} 에 넘긴다. */
    public static final int DETECTION_LENGTH = 12;

    private static final byte[] WEBP_FORM_TYPE = {'W', 'E', 'B', 'P'};
    private static final int WEBP_FORM_TYPE_OFFSET = 8;

    private final String extension;
    private final String contentType;
    private final byte[] signature;

    ImageFormat(String extension, String contentType, byte[] signature) {
        this.extension = extension;
        this.contentType = contentType;
        this.signature = signature;
    }

    /** 저장 키에 붙이는 확장자 (점 없음). */
    public String extension() {
        return extension;
    }

    /** 표준 MIME 타입 — 업로드 서명 · 저장 메타데이터에 이 값을 쓴다. */
    public String contentType() {
        return contentType;
    }

    /** 객체 앞부분으로 형식을 판별한다. 어느 형식의 시그니처로도 시작하지 않거나, 시그니처보다 짧으면(잘린 헤더 · 빈 파일) 비어 있다. */
    public static Optional<ImageFormat> detect(byte[] head) {
        if (head == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(format -> format.matches(head)).findFirst();
    }

    /** 요청의 Content-Type 을 형식으로 바꾼다. 대소문자는 가리지 않고, 파라미터({@code ;charset=…}) · 비표준 별칭({@code image/jpg})은 받지 않는다. */
    public static Optional<ImageFormat> ofContentType(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        String normalized = contentType.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(format -> format.contentType.equals(normalized)).findFirst();
    }

    private boolean matches(byte[] head) {
        if (!startsWith(head, signature, 0)) {
            return false;
        }
        return this != WEBP || startsWith(head, WEBP_FORM_TYPE, WEBP_FORM_TYPE_OFFSET);
    }

    private static boolean startsWith(byte[] head, byte[] expected, int offset) {
        if (head.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (head[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
