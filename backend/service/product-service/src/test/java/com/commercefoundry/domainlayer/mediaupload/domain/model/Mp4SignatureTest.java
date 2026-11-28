package com.commercefoundry.domainlayer.mediaupload.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class Mp4SignatureTest {

    /** size(big-endian) + ftyp + major + minor(0x200) + compatible. */
    private static byte[] ftyp(String major, String... compatible) {
        int size = 16 + compatible.length * 4;
        byte[] bytes = new byte[size];
        bytes[0] = (byte) (size >>> 24);
        bytes[1] = (byte) (size >>> 16);
        bytes[2] = (byte) (size >>> 8);
        bytes[3] = (byte) size;
        put(bytes, 4, "ftyp");
        put(bytes, 8, major);
        bytes[14] = 2;
        for (int i = 0; i < compatible.length; i++) {
            put(bytes, 16 + i * 4, compatible[i]);
        }
        return bytes;
    }

    private static void put(byte[] bytes, int offset, String value) {
        System.arraycopy(value.getBytes(StandardCharsets.ISO_8859_1), 0, bytes, offset, 4);
    }

    /** ftyp 뒤에 다른 box(moov 등)가 이어지는 실제 파일처럼 읽은 범위를 128바이트로 채운다. */
    private static byte[] followedByOtherBoxes(byte[] ftyp) {
        byte[] head = Arrays.copyOf(ftyp, Mp4Signature.DETECTION_LENGTH);
        head[ftyp.length + 3] = 8;
        System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, head, ftyp.length + 4, 4);
        return head;
    }

    @Test
    void acceptsCommonEncoderOutputs() {
        assertThat(Mp4Signature.matches(ftyp("isom", "isom", "iso2", "avc1", "mp41"))).as("ffmpeg 기본").isTrue();
        assertThat(Mp4Signature.matches(ftyp("mp42", "mp42", "iso2", "avc1", "mp41"))).as("HandBrake · Premiere").isTrue();
        assertThat(Mp4Signature.matches(ftyp("M4V ", "M4V ", "M4A ", "mp42", "isom"))).as("Apple M4V").isTrue();
        assertThat(Mp4Signature.matches(ftyp("iso5", "iso5", "iso6", "mp41"))).as("fragmented").isTrue();
        assertThat(Mp4Signature.matches(ftyp("mp41"))).as("compatible 없음").isTrue();
        assertThat(Mp4Signature.matches(followedByOtherBoxes(ftyp("isom", "isom", "avc1")))).as("뒤에 다른 box").isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso7", "iso8", "iso9",
        "mp41", "mp42", "avc1", "dash", "M4V ", "M4VH", "M4VP", "MSNV"})
    void acceptsEveryAllowedMajorBrand(String brand) {
        assertThat(Mp4Signature.matches(ftyp(brand))).isTrue();
    }

    @Test
    void acceptsUnknownMajorWhenCompatibleIsMp4Family() {
        assertThat(Mp4Signature.matches(ftyp("XAVC", "XAVC", "mp42", "iso2"))).as("Sony XAVC S").isTrue();
        assertThat(Mp4Signature.matches(ftyp("XAVC", "XAVC"))).as("MP4 계열 compatible 없음").isFalse();
    }

    /** 목록과 시험이 같이 움직이도록 NON_MP4_MAJOR_BRANDS 전체 + 3GPP 접두 예시. */
    static Stream<String> nonMp4Majors() {
        return Stream.concat(Mp4Signature.NON_MP4_MAJOR_BRANDS.stream(), Stream.of("3gp4", "3gp5", "3g2a"));
    }

    @ParameterizedTest
    @MethodSource("nonMp4Majors")
    void rejectsNonMp4MajorEvenWithMp4Compatible(String major) {
        assertThat(Mp4Signature.matches(ftyp(major, major, "isom", "mp42", "iso8"))).isFalse();
    }

    @Test
    void rejectsMalformedBoxes() {
        byte[] valid = ftyp("isom", "isom", "avc1");
        assertThat(Mp4Signature.matches(null)).isFalse();
        assertThat(Mp4Signature.matches(new byte[0])).isFalse();
        assertThat(Mp4Signature.matches(Arrays.copyOf(valid, 15))).as("16바이트 미만").isFalse();
        assertThat(Mp4Signature.matches(Arrays.copyOf(valid, valid.length - 1))).as("box 가 잘림").isFalse();

        byte[] wrongType = valid.clone();
        put(wrongType, 4, "moov");
        assertThat(Mp4Signature.matches(wrongType)).as("맨 앞이 ftyp 아님").isFalse();

        byte[] sizeZero = valid.clone();
        sizeZero[3] = 0;
        assertThat(Mp4Signature.matches(sizeZero)).as("size 0 = 파일 끝까지").isFalse();

        byte[] largeSize = valid.clone();
        largeSize[3] = 1;
        assertThat(Mp4Signature.matches(largeSize)).as("size 1 = 64비트 largesize").isFalse();

        byte[] tooSmall = valid.clone();
        tooSmall[3] = 12;
        assertThat(Mp4Signature.matches(tooSmall)).as("major · minor 보다 작음").isFalse();

        byte[] unaligned = Arrays.copyOf(valid, 26);
        unaligned[3] = 26;
        assertThat(Mp4Signature.matches(unaligned)).as("compatible 이 4바이트 단위가 아님").isFalse();

        byte[] huge = valid.clone();
        huge[0] = 0x7f;
        assertThat(Mp4Signature.matches(huge)).as("읽은 범위를 넘는 box").isFalse();
    }

    @Test
    void rejectsBoxLargerThanDetectionLength() {
        String[] compatible = new String[29];
        Arrays.fill(compatible, "isom");
        byte[] box = ftyp("isom", compatible);
        assertThat(box.length).isEqualTo(Mp4Signature.DETECTION_LENGTH + 4);
        assertThat(Mp4Signature.matches(box)).isFalse();
        String[] fits = new String[28];
        Arrays.fill(fits, "isom");
        assertThat(Mp4Signature.matches(ftyp("isom", fits))).as("128바이트 ftyp 은 받는다").isTrue();
    }

    @Test
    void rejectsNonPrintableBrandsAndOtherSignatures() {
        byte[] binaryMajor = ftyp("isom", "avc1");
        binaryMajor[8] = 0;
        assertThat(Mp4Signature.matches(binaryMajor)).isFalse();
        byte[] paddedCompatible = ftyp("mp42", "mp42", "isom");
        Arrays.fill(paddedCompatible, 20, 24, (byte) 0);
        assertThat(Mp4Signature.matches(paddedCompatible)).as("MP4 major 의 0 패딩 compatible 은 건너뛴다").isTrue();
        byte[] unknownMajorBinaryCompatible = ftyp("XAVC", "mp42");
        unknownMajorBinaryCompatible[19] = (byte) 0x80;
        assertThat(Mp4Signature.matches(unknownMajorBinaryCompatible)).as("깨진 compatible 은 MP4 근거가 되지 않는다").isFalse();
        byte[] highBitSize = ftyp("isom", "avc1");
        highBitSize[0] = (byte) 0x80;
        assertThat(Mp4Signature.matches(highBitSize)).as("size 최상위 비트 — uint32 로 읽어 음수가 되지 않는다").isFalse();
        assertThat(Mp4Signature.matches(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'})).isFalse();
        assertThat(Mp4Signature.matches("<html><body><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(Mp4Signature.matches(new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0})).as("WebM(EBML)").isFalse();
    }
}
