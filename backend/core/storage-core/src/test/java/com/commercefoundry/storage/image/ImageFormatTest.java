package com.commercefoundry.storage.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ImageFormatTest {

    private static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01);
    private static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D);
    private static final byte[] WEBP = bytes('R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P');

    static Stream<Arguments> accepted() {
        return Stream.of(
            Arguments.of("JPEG", JPEG, ImageFormat.JPEG),
            Arguments.of("PNG", PNG, ImageFormat.PNG),
            Arguments.of("WebP", WEBP, ImageFormat.WEBP),
            Arguments.of("JPEG 시그니처 3바이트만", bytes(0xFF, 0xD8, 0xFF), ImageFormat.JPEG),
            Arguments.of("판별 길이보다 길게 읽어도", concat(PNG, new byte[100]), ImageFormat.PNG));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accepted")
    @DisplayName("JPEG · PNG · WebP 는 맨 앞 시그니처로 판별한다")
    void detectsAcceptedFormats(String name, byte[] head, ImageFormat expected) {
        assertThat(ImageFormat.detect(head)).hasValue(expected);
    }

    static Stream<Arguments> rejected() {
        return Stream.of(
            Arguments.of("GIF", "GIF89a......".getBytes(StandardCharsets.US_ASCII)),
            Arguments.of("SVG", "<svg xmlns=\"http://www.w3.org/2000/svg\">".getBytes(StandardCharsets.US_ASCII)),
            Arguments.of("XML 선언으로 시작하는 SVG", "<?xml version=\"1.0\"?><svg>".getBytes(StandardCharsets.US_ASCII)),
            Arguments.of("HTML", "<!DOCTYPE html><html><script>".getBytes(StandardCharsets.US_ASCII)),
            Arguments.of("빈 배열", new byte[0]),
            Arguments.of("PNG 시그니처 7바이트", Arrays.copyOf(PNG, 7)),
            Arguments.of("JPEG 앞 2바이트만", bytes(0xFF, 0xD8)),
            Arguments.of("RIFF 인데 WAVE", bytes('R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'A', 'V', 'E')),
            Arguments.of("RIFF 인데 WEBP 앞에서 잘림", Arrays.copyOf(WEBP, 11)),
            Arguments.of("폴리글롯 — 앞은 HTML, 뒤에 JPEG", concat("<html>".getBytes(StandardCharsets.US_ASCII), JPEG)),
            Arguments.of("폴리글롯 — 앞 1바이트 뒤에 PNG", concat(bytes(0x00), PNG)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    @DisplayName("GIF · SVG · HTML · 빈 파일 · 잘린 헤더 · 다른 RIFF · 폴리글롯은 어느 형식도 아니다")
    void rejectsOtherContent(String name, byte[] head) {
        assertThat(ImageFormat.detect(head)).isEmpty();
    }

    @Test
    @DisplayName("null 은 어느 형식도 아니다")
    void rejectsNull() {
        assertThat(ImageFormat.detect(null)).isEmpty();
    }

    @Test
    @DisplayName("판별 길이는 가장 긴 시그니처(WebP 12바이트)다 — readRange 길이로 쓴다")
    void detectionLengthCoversLongestSignature() {
        assertThat(ImageFormat.DETECTION_LENGTH).isEqualTo(12);
        assertThat(ImageFormat.detect(Arrays.copyOf(WEBP, ImageFormat.DETECTION_LENGTH))).hasValue(ImageFormat.WEBP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "IMAGE/JPEG", " image/jpeg "})
    @DisplayName("Content-Type 은 대소문자 · 앞뒤 공백을 가리지 않는다")
    void parsesContentType(String contentType) {
        assertThat(ImageFormat.ofContentType(contentType)).hasValue(ImageFormat.JPEG);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "image/jpg", "image/gif", "image/svg+xml", "text/html", "image/png; charset=utf-8", "image/*"})
    @DisplayName("허용 목록 밖 · 비표준 별칭 · 파라미터가 붙은 Content-Type 은 받지 않는다")
    void rejectsOtherContentTypes(String contentType) {
        assertThat(ImageFormat.ofContentType(contentType)).isEmpty();
    }

    @Test
    @DisplayName("형식마다 확장자와 표준 MIME 타입을 가진다")
    void exposesExtensionAndContentType() {
        assertThat(ImageFormat.JPEG.extension()).isEqualTo("jpg");
        assertThat(ImageFormat.PNG.contentType()).isEqualTo("image/png");
        assertThat(ImageFormat.WEBP.extension()).isEqualTo("webp");
        assertThat(ImageFormat.WEBP.contentType()).isEqualTo("image/webp");
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}
