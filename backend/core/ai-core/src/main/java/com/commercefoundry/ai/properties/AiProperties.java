package com.commercefoundry.ai.properties;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Claude API 설정 ({@code ai.*}). AI 를 쓰는 서비스의 {@code *PropertiesConfig} 가 {@code AiPropertiesConfig} 를 {@code @Import} 한다.
 *
 * <p>기본값은 닫힌 쪽이다 — {@code enabled} 가 없으면 꺼진 채 뜨고, 나머지 값이 비어 있어도 기동한다. 생성자는 검사하지 않고 정규화만 한다.
 * 검사 규칙은 {@link #violations()} 한 곳에 있고, 기동 때는 {@code AiStartupGuard} 가, 직접 만들 때는 {@code AiClients.create} 가 같은 규칙을 부른다
 * ({@code StorageProperties} 와 같은 방식).
 *
 * <p>환경변수 이름이 프로퍼티와 1:1 이다 — {@code AI_API_KEY} → {@code ai.api-key}. 키는 Vault 에 둔다 ({@code secret-management.md}).
 * 툴링이 자동으로 읽는 {@code ANTHROPIC_API_KEY} 를 쓰지 않는다 — 개발 PC 셸의 개인 키가 서비스로 섞여 들어가지 않게.
 *
 * @param enabled          켜면 실제 API 를 부른다. 꺼져 있으면 {@code AiClient} 가 언제나 {@code AiException(DISABLED)} 다
 * @param apiKey           Claude API 키 ({@code AI_API_KEY}). 켜면 필수
 * @param baseUrl          API 주소. 비우면 SDK 기본값(공식 API)이다. 채우면 https 만 — http 는 {@code allowInsecureLoopback} 일 때 루프백만
 * @param defaultModel     기능 설정에 모델이 없을 때 쓰는 모델 ID. 기본 {@code claude-opus-5-5}
 * @param defaultMaxTokens 기능 설정에 없을 때의 최대 출력 토큰. 기본 16000 — 스트리밍 없는 호출이 HTTP 타임아웃 안에 끝나는 크기
 * @param connectTimeout   연결 타임아웃. 켜면 필수
 * @param readTimeout      응답(소켓 읽기) 타임아웃. 스트리밍이 아니라 생성이 끝날 때까지 바이트가 오지 않으므로 생성 시간을 덮어야 한다. 켜면 필수
 * @param requestTimeout   시도 한 번의 전체 시간 상한. 켜면 필수이고 {@code readTimeout} 이상이어야 한다
 * @param maxRetries       SDK 재시도 횟수(408 · 409 · 429 · 5xx · 연결 오류, {@code retry-after} 를 따른다). 기본 2, 0 이상 5 이하
 * @param maxConcurrency   이 프로세스의 동시 호출 상한. 기본 4, 1 이상
 * @param acquireTimeout   상한이 차 있을 때 자리를 기다리는 시간. 넘으면 {@code AiException(BUSY)} 다. 기본 10초
 * @param refusalFallback  모델이 안전 분류로 거절(refusal)하면 API 가 다른 모델로 다시 시도하게 한다(server-side fallback 베타, 공식 API 만).
 *                         기본 false — 닫힌 기본값. 거절은 악용 입력의 신호일 수 있고 답한 모델 · 비용이 바뀐다({@code AiResult.model}). 베타가 끝나면 400 이 된다
 * @param features         기능별 모델 · 최대 토큰 · effort. 키는 기능 이름({@code size-chart} 등 — 소문자 · 숫자 · {@code -})
 * @param masking          보내기 전 가리는 추가 패턴(주문번호 형식처럼 브랜드마다 다른 것). 기본 패턴은 언제나 적용된다
 * @param maxInputChars    시스템 + 사용자 프롬프트 글자 수 상한. 넘으면 보내지 않는다({@code INPUT_TOO_LONG}). 기본 200,000
 * @param allowInsecureLoopback 루프백 http {@code base-url} 을 허용한다 — 테스트 스텁 전용. 기본 false(운영은 https 만)
 * @param allowUnmetered   켜져 있는데 계량 인터셉터({@code AiCallInterceptor}) 빈이 없어도 기동하게 한다 — 사용량 원장 · 월 한도 없이 부르게 된다.
 *                         기본 false(닫힌 기본값). 로컬 실험 · 테스트 전용이다 — 운영(prod)에서 켜면 기동이 멈춘다
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(
    Boolean enabled,
    String apiKey,
    String baseUrl,
    @DefaultValue("claude-opus-5-5") String defaultModel,
    @DefaultValue("16000") Integer defaultMaxTokens,
    Duration connectTimeout, Duration readTimeout, Duration requestTimeout,
    @DefaultValue("2") Integer maxRetries,
    @DefaultValue("4") Integer maxConcurrency,
    @DefaultValue("10s") Duration acquireTimeout,
    @DefaultValue("false") Boolean refusalFallback,
    Map<String, Feature> features,
    Masking masking,
    @DefaultValue("200000") Integer maxInputChars,
    @DefaultValue("false") Boolean allowInsecureLoopback,
    @DefaultValue("false") Boolean allowUnmetered
) {

    /** Binder 는 풀리지 않은 {@code ${...}} 를 문자 그대로 남긴다. 환경변수가 없으면 값이 이 문자열을 품는다. */
    public static final String PLACEHOLDER_PREFIX = "${";

    public static final String ENABLED = "ai.enabled";
    public static final String API_KEY = "ai.api-key";
    public static final String BASE_URL = "ai.base-url";
    public static final String DEFAULT_MODEL = "ai.default-model";
    public static final String DEFAULT_MAX_TOKENS = "ai.default-max-tokens";
    public static final String CONNECT_TIMEOUT = "ai.connect-timeout";
    public static final String READ_TIMEOUT = "ai.read-timeout";
    public static final String REQUEST_TIMEOUT = "ai.request-timeout";
    public static final String MAX_RETRIES = "ai.max-retries";
    public static final String MAX_CONCURRENCY = "ai.max-concurrency";
    public static final String ACQUIRE_TIMEOUT = "ai.acquire-timeout";
    public static final String FEATURES = "ai.features";
    public static final String MASKING_PATTERNS = "ai.masking.patterns";
    public static final String MAX_INPUT_CHARS = "ai.max-input-chars";
    public static final String ALLOW_UNMETERED = "ai.allow-unmetered";
    public static final String ALLOW_INSECURE_LOOPBACK = "ai.allow-insecure-loopback";
    public static final int DEFAULT_MAX_INPUT_CHARS = 200_000;
    /** 기본 마스킹 패턴의 자리표시와 겹치는 이름 — 설정 패턴으로 쓰지 못한다 ({@code PiiMasker.RESERVED_NAMES} 와 같다). */
    public static final Set<String> RESERVED_PATTERN_NAMES = Set.of("pii", "email", "rrn", "card", "phone", "account", "address");

    /** {@code @DefaultValue} 와 같다. 최신 Opus — 기능마다 더 싼 모델로 낮출지는 운영 판단이라 {@code features} 로 둔다. */
    public static final String DEFAULT_MODEL_ID = "claude-opus-5-5";
    public static final int DEFAULT_MAX_TOKENS_VALUE = 16000;
    public static final int MAX_RETRIES_LIMIT = 5;
    /** API 의 effort 값. 모델마다 받는 값이 다를 수 있다 — 모델 문서를 본다. */
    public static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    public AiProperties {
        enabled = Boolean.TRUE.equals(enabled);
        apiKey = strip(apiKey);
        baseUrl = isAbsent(baseUrl) ? null : baseUrl.strip();
        // 직접 만들 때(테스트 · 팩토리)도 @DefaultValue 와 같은 기본값이 들어가게 한다
        defaultModel = defaultModel == null ? DEFAULT_MODEL_ID : defaultModel.strip();
        defaultMaxTokens = defaultMaxTokens == null ? DEFAULT_MAX_TOKENS_VALUE : defaultMaxTokens;
        maxRetries = maxRetries == null ? 2 : maxRetries;
        maxConcurrency = maxConcurrency == null ? 4 : maxConcurrency;
        acquireTimeout = acquireTimeout == null ? Duration.ofSeconds(10) : acquireTimeout;
        refusalFallback = Boolean.TRUE.equals(refusalFallback);
        features = features == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(features));
        masking = masking == null ? new Masking(null) : masking;
        maxInputChars = maxInputChars == null ? DEFAULT_MAX_INPUT_CHARS : maxInputChars;
        allowInsecureLoopback = Boolean.TRUE.equals(allowInsecureLoopback);
        allowUnmetered = Boolean.TRUE.equals(allowUnmetered);
    }

    /**
     * 기능 하나의 설정.
     *
     * @param model     모델 ID. 비우면 {@code default-model}
     * @param maxTokens 최대 출력 토큰. 비우면 {@code default-max-tokens}
     * @param effort    {@code low} · {@code medium} · {@code high} · {@code xhigh} · {@code max}. 비우면 모델 기본값
     * @param promptVersion 쓸 프롬프트 템플릿 버전 — 리소스 {@code ai/prompts/<기능>/v<버전>/}. 1 이상. 템플릿을 쓰는 기능은 필수다.
     *                  배포된 버전의 파일은 고치지 않고 새 버전을 만들어 이 값을 올린다 — 되돌리기도 이 값이다 ({@code bootstrap-conventions.md} AI)
     */
    public record Feature(String model, Integer maxTokens, String effort, Integer promptVersion) {

        /** 생성자가 둘이라 바인딩할 생성자를 지정한다 — 없으면 Binder 가 고르지 못해 기능 설정이 조용히 빠진다. */
        @ConstructorBinding
        public Feature {
            model = isAbsent(model) ? null : model.strip();
            effort = isAbsent(effort) ? null : effort.strip().toLowerCase(Locale.ROOT);
        }

        public Feature(String model, Integer maxTokens, String effort) {
            this(model, maxTokens, effort, null);
        }
    }

    /**
     * 추가 마스킹 패턴.
     *
     * @param patterns 이름 → 정규식. 맞는 부분을 {@code [이름 대문자]} 로 바꾼다 — {@code order-number} → {@code [ORDER_NUMBER]}
     */
    public record Masking(Map<String, String> patterns) {

        public Masking {
            patterns = patterns == null ? Map.of() : Map.copyOf(patterns);
        }
    }

    /** 기능 설정을 기본값으로 채운 결과. 설정에 없는 기능은 기본값 그대로다. */
    public ResolvedFeature resolve(String feature) {
        Feature settings = features.get(feature);
        String model = settings == null || settings.model() == null ? defaultModel : settings.model();
        int maxTokens = settings == null || settings.maxTokens() == null ? defaultMaxTokens : settings.maxTokens();
        return new ResolvedFeature(model, maxTokens, settings == null ? null : settings.effort());
    }

    /** 호출 한 번에 쓸 모델 · 최대 토큰 · effort({@code null} 이면 모델 기본값). */
    public record ResolvedFeature(String model, int maxTokens, String effort) {

    }

    /**
     * 이 설정으로 클라이언트를 만들어도 되는지 본다. 꺼져 있으면 통과다.
     *
     * <p>켜져 있을 때: 키가 비었거나 풀리지 않았다 · {@code base-url} 이 https(루프백만 http)가 아니다 · 기본 모델이 비었다 ·
     * 토큰 수가 1 미만이다 · 타임아웃이 없거나 0 이하이거나 요청 상한이 응답 타임아웃보다 짧다 · 재시도가 0~5 밖이다 ·
     * 동시 상한이 1 미만이다 · 기능 이름 · effort 가 규칙에 맞지 않는다 · 마스킹 패턴이 정규식이 아니다.
     *
     * @return 위반마다 프로퍼티 이름과 설명. 설명에 설정값(키 · 주소 · 패턴)을 넣지 않는다
     */
    public List<Violation> violations() {
        List<Violation> violations = new ArrayList<>();
        if (!enabled) {
            return violations;
        }
        if (isAbsent(apiKey)) {
            violations.add(new Violation(API_KEY, describe(API_KEY) + " 가 비었거나 플레이스홀더가 풀리지 않았습니다. Vault 에 등록한 값을 넣으세요 (secret-management.md)"));
        }
        if (baseUrl != null) {
            urlProblem(baseUrl, allowInsecureLoopback).ifPresent(problem -> violations.add(new Violation(BASE_URL, describe(BASE_URL) + " " + problem)));
        }
        if (isAbsent(defaultModel)) {
            violations.add(new Violation(DEFAULT_MODEL, DEFAULT_MODEL + "(기본 모델 ID)가 비었습니다"));
        }
        if (defaultMaxTokens == null || defaultMaxTokens < 1) {
            violations.add(new Violation(DEFAULT_MAX_TOKENS, DEFAULT_MAX_TOKENS + "(최대 출력 토큰)는 1 이상이어야 합니다"));
        }
        requirePositive(CONNECT_TIMEOUT, "연결 타임아웃", connectTimeout, violations);
        requirePositive(READ_TIMEOUT, "응답 타임아웃", readTimeout, violations);
        requirePositive(REQUEST_TIMEOUT, "시도 한 번의 전체 시간 상한", requestTimeout, violations);
        if (isPositive(requestTimeout) && isPositive(readTimeout) && requestTimeout.compareTo(readTimeout) < 0) {
            violations.add(new Violation(REQUEST_TIMEOUT, REQUEST_TIMEOUT + " 는 " + READ_TIMEOUT + " 이상이어야 합니다 — 짧으면 응답 타임아웃이 뜻을 잃습니다"));
        }
        if (maxRetries < 0 || maxRetries > MAX_RETRIES_LIMIT) {
            violations.add(new Violation(MAX_RETRIES, MAX_RETRIES + " 는 0 이상 " + MAX_RETRIES_LIMIT + " 이하여야 합니다 — 시도마다 비용이 들 수 있습니다"));
        }
        if (maxConcurrency < 1) {
            violations.add(new Violation(MAX_CONCURRENCY, MAX_CONCURRENCY + "(동시 호출 상한)는 1 이상이어야 합니다"));
        }
        requirePositive(ACQUIRE_TIMEOUT, "동시 호출 자리 대기 시간", acquireTimeout, violations);
        if (maxInputChars < 1) {
            violations.add(new Violation(MAX_INPUT_CHARS, MAX_INPUT_CHARS + "(입력 글자 수 상한)는 1 이상이어야 합니다"));
        }
        features.forEach((name, feature) -> checkFeature(name, feature, violations));
        masking.patterns().forEach((name, regex) -> checkPattern(name, regex, violations));
        return violations;
    }

    /**
     * 계량 인터셉터 개수가 맞는지 본다 — 켜져 있으면 정확히 하나여야 한다({@link #allowUnmetered()} 면 0 도 된다). 꺼져 있으면 통과다.
     * 기동 때는 {@code AiStartupGuard} 가 빈 정의 개수로, 직접 만들 때는 {@code AiClients.create} 가 넘긴 목록 크기로 부른다.
     *
     * <p>둘 이상을 막는 이유: 앞선 인터셉터의 {@code before} 가 예약한 뒤 다음 {@code before} 가 거절하면 앞선 예약을 풀 보상 순서를 정해야 한다.
     * 원장 · 한도는 한 서비스(operations)에 하나뿐이라 그 복잡도를 지금 들이지 않는다.
     */
    public Optional<Violation> meteringViolation(int interceptorCount) {
        if (!enabled) {
            return Optional.empty();
        }
        if (interceptorCount > 1) {
            return Optional.of(new Violation(ALLOW_UNMETERED, "계량 인터셉터(AiCallInterceptor) 빈이 " + interceptorCount + "개입니다 — 하나만 둡니다"));
        }
        if (interceptorCount == 0 && !allowUnmetered) {
            return Optional.of(new Violation(ALLOW_UNMETERED, "ai.enabled=true 인데 계량 인터셉터(AiCallInterceptor) 빈이 없습니다 — 사용량 원장 · 월 한도 없이 부르게 됩니다. "
                + "원장을 가진 서비스(operations)에서만 켜거나, 로컬 실험이면 " + ALLOW_UNMETERED + "=true 를 명시하세요"));
        }
        return Optional.empty();
    }

    /** 값이 없거나 공백뿐이거나 플레이스홀더가 풀리지 않았으면 {@code true} 다. */
    public static boolean isAbsent(String value) {
        return value == null || value.isBlank() || value.contains(PLACEHOLDER_PREFIX);
    }

    /** {@code ai.api-key} → {@code ai.api-key(환경변수 AI_API_KEY)}. 이름이 Vault 키 이름과 같다. */
    public static String describe(String property) {
        return "%s(환경변수 %s)".formatted(property, property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
    }

    /** 키가 로그 · 오류 메시지로 새지 않게 가린다. 마스킹 패턴은 개수만 싣는다. */
    @Override
    public String toString() {
        return "AiProperties[enabled=" + enabled + ", apiKey=" + (apiKey == null ? "null" : "****") + ", baseUrl=" + baseUrl
            + ", defaultModel=" + defaultModel + ", defaultMaxTokens=" + defaultMaxTokens
            + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + ", requestTimeout=" + requestTimeout
            + ", maxRetries=" + maxRetries + ", maxConcurrency=" + maxConcurrency + ", acquireTimeout=" + acquireTimeout
            + ", refusalFallback=" + refusalFallback + ", features=" + features + ", maskingPatterns=" + masking.patterns().size()
            + ", maxInputChars=" + maxInputChars + ", allowInsecureLoopback=" + allowInsecureLoopback + ", allowUnmetered=" + allowUnmetered + "]";
    }

    /**
     * 어긋난 설정 하나.
     *
     * @param property 프로퍼티 이름
     * @param message  설명. 설정값을 싣지 않는다
     */
    public record Violation(String property, String message) {

    }

    private static void checkFeature(String name, Feature feature, List<Violation> violations) {
        String property = FEATURES + "." + name;
        if (!NAME.matcher(name).matches()) {
            violations.add(new Violation(FEATURES, FEATURES + " 의 기능 이름은 소문자 · 숫자 · '-' 로 1~63자여야 합니다"));
            return;
        }
        if (feature == null) {
            return;
        }
        if (feature.maxTokens() != null && feature.maxTokens() < 1) {
            violations.add(new Violation(property, property + ".max-tokens 는 1 이상이어야 합니다"));
        }
        if (feature.effort() != null && !EFFORTS.contains(feature.effort())) {
            violations.add(new Violation(property, property + ".effort 는 " + String.join(" · ", EFFORTS.stream().sorted().toList()) + " 중 하나여야 합니다"));
        }
        if (feature.promptVersion() != null && feature.promptVersion() < 1) {
            violations.add(new Violation(property, property + ".prompt-version 은 1 이상이어야 합니다"));
        }
    }

    private static void checkPattern(String name, String regex, List<Violation> violations) {
        String property = MASKING_PATTERNS + "." + name;
        if (!NAME.matcher(name).matches()) {
            violations.add(new Violation(MASKING_PATTERNS, MASKING_PATTERNS + " 의 이름은 소문자 · 숫자 · '-' 로 1~63자여야 합니다"));
            return;
        }
        if (RESERVED_PATTERN_NAMES.contains(name)) {
            violations.add(new Violation(property, property + " 는 기본 패턴과 이름이 겹칩니다 — " + String.join(" · ", RESERVED_PATTERN_NAMES.stream().sorted().toList())
                + " 는 쓰지 못합니다"));
            return;
        }
        if (isAbsent(regex)) {
            violations.add(new Violation(property, property + " 가 비었습니다"));
            return;
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            // 원인을 잇지 않는다 — 메시지에 패턴 원문이 들어 있다
            violations.add(new Violation(property, property + " 가 정규식이 아닙니다"));
        }
    }

    private static void requirePositive(String property, String label, Duration value, List<Violation> violations) {
        if (!isPositive(value)) {
            violations.add(new Violation(property, "%s(%s)이 없거나 0 이하입니다. 서비스 yml 에 양수로 명시하세요".formatted(property, label)));
        }
    }

    private static boolean isPositive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }

    /** 절대 https URL(허용하면 루프백 http)이고, 사용자 정보 · 쿼리 · 프래그먼트가 없어야 한다. 값은 싣지 않는다. */
    private static Optional<String> urlProblem(String value, boolean allowInsecureLoopback) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return Optional.of("가 URL 형식이 아닙니다");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null) {
            return Optional.of("는 https://호스트 형식의 절대 URL 이어야 합니다");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return Optional.of("에 사용자 정보(user@) · 쿼리(?) · 프래그먼트(#)를 넣지 않습니다");
        }
        if (scheme.equals("http") && !(allowInsecureLoopback && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)))) {
            return Optional.of("는 https 여야 합니다 — http 는 ai.allow-insecure-loopback=true 일 때 루프백 호스트(localhost · 127.0.0.1 · [::1])만 허용합니다(테스트 전용)");
        }
        return Optional.empty();
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
