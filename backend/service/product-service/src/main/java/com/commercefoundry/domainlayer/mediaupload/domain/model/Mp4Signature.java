package com.commercefoundry.domainlayer.mediaupload.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MP4(ISO BMFF) 판별 (#796). 확장자 · Content-Type 은 위조할 수 있으므로 객체 맨 앞의 {@code ftyp} box 를 조건부 range 로 읽어 본다.
 *
 * <p>모양 — 오프셋 0~3 box size(big-endian uint32), 4~7 {@code ftyp}, 8~11 major brand, 12~15 minor version, 16~ compatible brand 4바이트씩.
 * box 전체가 읽은 범위 안에 있어야 한다: size 는 {@value #MIN_BOX_SIZE} 이상 {@value #DETECTION_LENGTH} 이하이고 4의 배수다.
 * size 0(파일 끝까지) · 1(64비트 largesize)은 ftyp 에 쓰지 않는 값이라 거절한다. major brand 는 4바이트 출력 가능 ASCII 여야 하고,
 * 출력 불가 compatible brand(0 패딩 등)는 거절하지 않고 판정에서 뺀다.
 *
 * <p>판정 — major brand 가 {@link #MP4_BRANDS} 면 받는다. 아니면 major 가 MP4 가 아닌 같은 계열 형식({@link #NON_MP4_MAJOR_BRANDS} ·
 * {@code 3g} 로 시작하는 3GPP/3GPP2)이 아니고 compatible brand 중 하나가 {@link #MP4_BRANDS} 일 때 받는다
 * (예: Sony XAVC S 의 major {@code XAVC} + compatible {@code mp42}).
 *
 * <p>이 판별은 브라우저가 재생할 만한 MP4 컨테이너인지 거르는 것이지 전체 디코딩 · 코덱 검증이 아니다. 맨 앞이 ftyp 여야 하므로
 * HTML · SVG 로 시작하는 폴리글롯은 통과하지 못한다. 그 뒤의 방어는 이미지와 같다 — 복사본 Content-Type 고정, 공개 도메인의 nosniff · origin 분리.
 */
public final class Mp4Signature {

    /** 앞에서 읽는 바이트 수이자 받는 ftyp box 의 최대 크기 — compatible brand 28개까지다. 일반 인코더(ffmpeg · HandBrake · Premiere)는 32바이트 안팎이다. */
    public static final int DETECTION_LENGTH = 128;
    /** size 4 + type 4 + major 4 + minor 4. */
    static final int MIN_BOX_SIZE = 16;

    /**
     * MP4 계열 brand — 근거.
     * <ul>
     *   <li>{@code isom} · {@code iso2}~{@code iso9}: ISO/IEC 14496-12(ISO BMFF) 판별 brand 와 판(edition)별 brand</li>
     *   <li>{@code mp41} · {@code mp42}: ISO/IEC 14496-14(MP4 file format) v1 · v2</li>
     *   <li>{@code avc1}: ISO/IEC 14496-15(AVC file format)</li>
     *   <li>{@code dash}: ISO/IEC 23009-1(MPEG-DASH) 세그먼트 · 파일</li>
     *   <li>{@code M4V } · {@code M4VH} · {@code M4VP}: Apple iTunes 영상(MP4RA 등록)</li>
     *   <li>{@code MSNV}: Sony PSP MP4(MP4RA 등록)</li>
     * </ul>
     */
    static final Set<String> MP4_BRANDS = Set.of(
        "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso7", "iso8", "iso9",
        "mp41", "mp42", "avc1", "dash", "M4V ", "M4VH", "M4VP", "MSNV"
    );

    /**
     * major 이면 compatible brand 가 MP4 계열이어도 받지 않는 형식 — 같은 ISO BMFF 지만 video/mp4 가 아니다.
     * QuickTime({@code qt  }, video/quicktime), iTunes 음성({@code M4A } · {@code M4B } · {@code M4P }),
     * HEIF · AVIF 정지 이미지 · 이미지 시퀀스(ISO/IEC 23008-12 · AV1 Image File Format), Canon RAW({@code crx }),
     * JPEG 2000 계열(ISO/IEC 15444, Motion JPEG 2000 {@code mjp2} 포함). 3GPP/3GPP2 는 {@code 3g} 접두로 따로 거른다.
     */
    static final Set<String> NON_MP4_MAJOR_BRANDS = Set.of(
        "qt  ", "M4A ", "M4B ", "M4P ",
        "mif1", "msf1", "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "avif", "avis",
        "crx ", "jp2 ", "jpx ", "jpm ", "mjp2"
    );

    private Mp4Signature() {}

    /** 앞부분이 받는 MP4 ftyp box 면 true. null · 짧은 헤더 · 잘린 box 는 false 다. */
    public static boolean matches(byte[] head) {
        if (head == null || head.length < MIN_BOX_SIZE) {
            return false;
        }
        long size = uint32(head, 0);
        if (size < MIN_BOX_SIZE || size > DETECTION_LENGTH || size % 4 != 0 || head.length < size) {
            return false;
        }
        if (head[4] != 'f' || head[5] != 't' || head[6] != 'y' || head[7] != 'p') {
            return false;
        }
        String major = brand(head, 8);
        if (major == null) {
            return false;
        }
        if (MP4_BRANDS.contains(major)) {
            return true;
        }
        // 출력 불가 compatible(0 패딩 등)은 판정에 쓰지 않고 건너뛴다 — 정상 파일을 거절할 뿐 막는 것이 없다
        List<String> compatible = new ArrayList<>();
        for (int offset = MIN_BOX_SIZE; offset < size; offset += 4) {
            String brand = brand(head, offset);
            if (brand != null) {
                compatible.add(brand);
            }
        }
        if (NON_MP4_MAJOR_BRANDS.contains(major) || major.startsWith("3g")) {
            return false;
        }
        return compatible.stream().anyMatch(MP4_BRANDS::contains);
    }

    private static long uint32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL) << 24 | (bytes[offset + 1] & 0xFFL) << 16 | (bytes[offset + 2] & 0xFFL) << 8 | bytes[offset + 3] & 0xFFL;
    }

    /** 출력 가능 ASCII(0x20~0x7E) 4바이트면 문자열, 아니면 null. */
    private static String brand(byte[] bytes, int offset) {
        char[] chars = new char[4];
        for (int i = 0; i < 4; i++) {
            int value = bytes[offset + i] & 0xFF;
            if (value < 0x20 || value > 0x7E) {
                return null;
            }
            chars[i] = (char) value;
        }
        return new String(chars);
    }
}
