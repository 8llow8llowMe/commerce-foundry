package com.commercefoundry.ai.prompt;

import com.commercefoundry.ai.client.AiRequest;
import com.commercefoundry.ai.properties.AiProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 기능별 · 버전별 프롬프트 템플릿을 클래스패스 리소스에서 읽는다 ({@code bootstrap-conventions.md} AI).
 *
 * <pre>
 * src/main/resources/ai/prompts/&lt;기능&gt;/v&lt;버전&gt;/user.txt     (필수)
 * src/main/resources/ai/prompts/&lt;기능&gt;/v&lt;버전&gt;/system.txt   (선택)
 * </pre>
 *
 * <p>쓰는 버전은 설정 {@code ai.features.<기능>.prompt-version} 이다. 배포된 버전의 파일은 고치지 않는다 — 문구를 바꾸면 새 버전 디렉터리를 만들고
 * 설정을 올린다(로그의 {@code promptVersion} 으로 결과를 문구와 대조하고, 되돌리기는 설정만 내린다). 문구는 브랜드 · 고객사 몫이라 ai-core 가 아니라
 * 쓰는 서비스의 리소스에 둔다. 파일은 UTF-8 이다(BOM 은 지운다).
 *
 * <p>설정에 버전이 있는 기능은 빈을 만들 때 모두 읽어 검사한다 — 파일이 없거나 · UTF-8 이 아니거나 · 자리 표기가 틀리면 기동이 멈춘다.
 */
public final class PromptTemplates {

    public static final String ROOT = "ai/prompts";
    /** UTF-8 BOM(U+FEFF). 소스에 보이지 않는 문자를 넣지 않으려고 코드 값으로 적는다. */
    private static final char BOM = (char) 0xFEFF;

    private final ClassLoader classLoader;
    private final int maxInputChars;
    private final Map<String, Integer> versions;
    private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();

    public PromptTemplates(ClassLoader classLoader, AiProperties properties) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.maxInputChars = properties.maxInputChars();
        Map<String, Integer> configured = new HashMap<>();
        properties.features().forEach((name, feature) -> {
            if (feature != null && feature.promptVersion() != null) {
                configured.put(name, feature.promptVersion());
            }
        });
        this.versions = Map.copyOf(configured);
        versions.forEach(this::load);
    }

    /**
     * 기능의 설정 버전 템플릿.
     *
     * @throws IllegalStateException 설정에 그 기능의 {@code prompt-version} 이 없을 때
     */
    public PromptTemplate get(String feature) {
        Integer version = versions.get(feature);
        if (version == null) {
            throw new IllegalStateException("ai.features." + feature + ".prompt-version 이 없습니다 — 템플릿을 쓰는 기능은 버전을 설정합니다");
        }
        return load(feature, version);
    }

    /** 특정 버전 — 버전 비교 · 되돌리기 검증용. */
    public PromptTemplate get(String feature, int version) {
        return load(feature, version);
    }

    /** 기능 이름 · 버전을 검사한 뒤에만 경로를 만든다 — 설정이 꺼져 있어 {@code AiProperties} 검사가 돌지 않아도, 호출자가 다른 값을 넘겨도 같다. */
    private PromptTemplate load(String feature, int version) {
        if (feature == null || !AiRequest.FEATURE_NAME.matcher(feature).matches()) {
            throw new IllegalArgumentException("기능 이름은 소문자 · 숫자 · '-' 로 1~63자여야 합니다");
        }
        if (version < 1) {
            throw new IllegalArgumentException("프롬프트 버전은 1 이상이어야 합니다");
        }
        return cache.computeIfAbsent(feature + "@" + version, key -> {
            String base = ROOT + "/" + feature + "/v" + version + "/";
            String user = read(base + "user.txt");
            if (user == null || user.isBlank()) {
                throw new IllegalStateException("프롬프트 템플릿이 없습니다: classpath:" + base + "user.txt");
            }
            String system = read(base + "system.txt");
            int length = user.length() + (system == null ? 0 : system.length());
            if (length > maxInputChars) {
                // 그대로 두면 기동은 되고 모든 호출이 INPUT_TOO_LONG 이 된다
                throw new IllegalStateException("프롬프트 템플릿이 ai.max-input-chars 보다 깁니다: classpath:" + base);
            }
            return new PromptTemplate(feature, version, system, user);
        });
    }

    private String read(String path) {
        try (InputStream in = classLoader.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(in.readAllBytes()))
                .toString();
            return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("프롬프트 템플릿이 UTF-8 이 아닙니다: classpath:" + path);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 템플릿을 읽지 못했습니다: classpath:" + path, e);
        }
    }

    /** 확인용 — 설정된 기능 → 버전. */
    public Map<String, Integer> versions() {
        return versions;
    }
}
