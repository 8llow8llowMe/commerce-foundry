package com.commercefoundry.global.media;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * 어드민이 저장하는 미디어 주소의 허용 판정 — <b>스토어 미디어 저장소의 공개 주소({@code store.media.public-base-url}) 아래만 받는다.</b>
 * 외부 이미지 주소를 그대로 받으면 고객 화면이 남의 서버(추적 · 바뀐 내용 · http 혼합 콘텐츠)를 부르게 된다.
 *
 * <p>상품 · 전시 등 여러 컨텍스트가 쓰게 global 에 둔다(전시 #63 이 처음 쓰고, 상품 이미지 #9 가 같은 규칙을 쓸 수 있다). 스프링 · 도메인 타입에 의존하지 않는다.
 *
 * <ul>
 *   <li><b>공개 주소</b> — 비면 "설정 안 됨" 이다({@link #isConfigured()} false). 이때는 어떤 주소도 받지 않는다(닫힌 기본값) — 호출한 쪽이 따로 알린다.
 *       값이 있으면 https 여야 하고 루프백 호스트(localhost · 127.0.0.1 · [::1])만 http 를 허용한다. 쿼리 · 프래그먼트 · 사용자 정보가 있으면 기동하지 않는다.</li>
 *   <li><b>주소</b> — {@code 공개 주소 + "/"} 로 시작하고 그 뒤 키가 허용 문자 집합({@link #isAllowedKey})으로만 이뤄져야 한다. 그 위에
 *       {@code ..} 와 그 인코딩({@code %2e}), 인코딩한 경로 구분자({@code %2f} · {@code %5c})를 거절한다 — 저장소 키 하나를 가리키는 주소만 남긴다.</li>
 * </ul>
 *
 * <p>거절 사유를 밖으로 내지 않는다 — 오류 메시지에 공개 주소를 싣지 않기 위해서다.
 */
public final class MediaUrlPolicy {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    /** 허용 문자 집합을 지난 키에 더 거는 거절 — 경로 이동과 그 인코딩, 인코딩한 경로 구분자. 대소문자를 가리지 않는다. */
    private static final String[] FORBIDDEN_SEQUENCES = {"..", "%2e", "%2f", "%5c"};

    /** 정규화한 공개 주소(끝 {@code /} 없음). 비어 있으면 설정 안 됨이다. */
    private final String baseUrl;

    private MediaUrlPolicy(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * @param publicBaseUrl 정규화한 공개 주소(끝 {@code /} 없음). null · 빈 값이면 설정 안 됨이다
     * @throws IllegalArgumentException 값이 있는데 https(루프백만 http) 절대 주소가 아니다 — 메시지에 값을 싣지 않는다
     */
    public static MediaUrlPolicy of(String publicBaseUrl) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.strip();
        if (!base.isEmpty() && !isValidBase(base)) {
            throw new IllegalArgumentException("store.media.public-base-url 은 https 절대 주소여야 합니다 (루프백 호스트만 http, 쿼리 · 프래그먼트 없음).");
        }
        return new MediaUrlPolicy(base);
    }

    public boolean isConfigured() {
        return !baseUrl.isEmpty();
    }

    /** 설정 안 됨이면 늘 false 다. */
    public boolean isAllowed(String url) {
        if (!isConfigured() || url == null) {
            return false;
        }
        String prefix = baseUrl + "/";
        if (!url.startsWith(prefix)) {
            return false;
        }
        String key = url.substring(prefix.length());
        if (!isAllowedKey(key)) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String sequence : FORBIDDEN_SEQUENCES) {
            if (lower.contains(sequence)) {
                return false;
            }
        }
        return parse(url) != null;
    }

    /**
     * 키(공개 주소 + {@code /} 뒤)는 허용 문자 집합으로만 판정한다 — 영숫자 · {@code . _ ~ - /} 와 {@code %XX}(16진수 두 자리) 퍼센트 인코딩만 받는다.
     * 빈 키, {@code /} 로 시작하는 키(경계의 {@code //}), 연속된 {@code //}, {@code /} 로 끝나는 키(빈 마지막 구간)는 거절한다.
     *
     * <p>{@code ' ( ) , ; : = + $ @ ! * [ ]} 같은 하위 구분자 · 예약 문자를 막는 것은 주소가 CSS {@code url(...)} · HTML 속성에 그대로 들어갈 때
     * 따옴표 · 괄호로 문맥을 빠져나가거나, 매개변수 꼴({@code ;jsessionid=} · {@code key=value})로 추적 값을 싣지 못하게 하려는 것이다.
     */
    private static boolean isAllowedKey(String key) {
        if (key.isEmpty() || key.startsWith("/") || key.endsWith("/") || key.contains("//")) {
            return false;
        }
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            if (character == '%') {
                if (index + 2 >= key.length() || !isHex(key.charAt(index + 1)) || !isHex(key.charAt(index + 2))) {
                    return false;
                }
                index += 2;
            } else if (!isUnreservedOrSlash(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUnreservedOrSlash(char character) {
        return character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z' || character >= '0' && character <= '9'
            || character == '.' || character == '_' || character == '~' || character == '-' || character == '/';
    }

    private static boolean isHex(char character) {
        return character >= '0' && character <= '9' || character >= 'a' && character <= 'f' || character >= 'A' && character <= 'F';
    }

    private static boolean isValidBase(String base) {
        if (base.codePoints().anyMatch(MediaUrlPolicy::isRejectedCharacter) || base.contains("\\")) {
            return false;
        }
        URI uri = parse(base);
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null
            || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return scheme.equals("https") || scheme.equals("http") && LOOPBACK_HOSTS.contains(host);
    }

    /** 공백(유니코드 공백 포함) · 제어 문자 · 서식 문자 · 짝 없는 서로게이트. */
    private static boolean isRejectedCharacter(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
            || type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE;
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException exception) {
            return null;
        }
    }
}
