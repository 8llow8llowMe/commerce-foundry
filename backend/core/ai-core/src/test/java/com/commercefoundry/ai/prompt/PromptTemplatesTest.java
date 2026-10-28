package com.commercefoundry.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.ai.properties.AiProperties.Feature;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 템플릿은 테스트 리소스 {@code ai/prompts/**} 다. */
class PromptTemplatesTest {

    private final ClassLoader classLoader = getClass().getClassLoader();

    @Test
    @DisplayName("설정 버전의 템플릿을 리소스에서 읽는다 — system.txt 는 선택, user.txt 는 필수")
    void loadsConfiguredVersion() {
        PromptTemplates templates = new PromptTemplates(classLoader, properties(Map.of(
            "size-chart", new Feature(null, null, null, 1),
            "cs-reply", new Feature(null, null, null, 1))));

        PromptTemplate sizeChart = templates.get("size-chart");
        assertThat(sizeChart.version()).isEqualTo(1);
        assertThat(sizeChart.system()).startsWith("너는 한복 쇼핑몰의 사이즈표 작성 도우미다");
        assertThat(sizeChart.slots()).containsExactly("productName", "measurements");
        assertThat(templates.get("cs-reply").system()).isNull();
        assertThat(templates.get("cs-reply").slots()).containsExactly("pii:customerName", "inquiry");
    }

    @Test
    @DisplayName("버전은 설정으로 바꾼다 — 같은 기능의 다른 버전 디렉터리를 읽는다")
    void switchesVersionByConfiguration() {
        PromptTemplates v2 = new PromptTemplates(classLoader, properties(Map.of("size-chart", new Feature(null, null, null, 2))));

        assertThat(v2.get("size-chart").version()).isEqualTo(2);
        assertThat(v2.get("size-chart").user()).contains("사이즈별 표로");
        assertThat(v2.get("size-chart", 1).user()).doesNotContain("사이즈별 표로");
    }

    @Test
    @DisplayName("설정된 버전의 파일이 없으면 만들 때(기동 때) 실패한다")
    void failsFastOnMissingFile() {
        assertThatThrownBy(() -> new PromptTemplates(classLoader, properties(Map.of("size-chart", new Feature(null, null, null, 9)))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("ai/prompts/size-chart/v9/user.txt");
    }

    @Test
    @DisplayName("버전 설정이 없는 기능을 찾으면 실패한다")
    void failsWithoutVersion() {
        PromptTemplates templates = new PromptTemplates(classLoader, properties(Map.of()));

        assertThatThrownBy(() -> templates.get("size-chart")).isInstanceOf(IllegalStateException.class).hasMessageContaining("prompt-version");
    }

    @Test
    @DisplayName("기능 이름 · 버전을 경로에 넣기 전에 검사한다 — 설정이 꺼져 있어도, 호출자가 다른 값을 넘겨도 같다")
    void validatesFeatureAndVersion() {
        PromptTemplates templates = new PromptTemplates(classLoader, properties(Map.of()));

        assertThatThrownBy(() -> templates.get("../../x", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> templates.get("size-chart", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromptTemplates(classLoader, properties(Map.of("../x", new Feature(null, null, null, 1)))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("템플릿이 ai.max-input-chars 보다 길면 기동 때 실패한다 — 그대로 두면 모든 호출이 INPUT_TOO_LONG 이다")
    void rejectsTemplateLongerThanInputLimit() {
        AiProperties tight = new AiProperties(false, null, null, null, null, null, null, null, null, null, null, null,
            Map.of("size-chart", new Feature(null, null, null, 1)), null, 10, null, null);

        assertThatThrownBy(() -> new PromptTemplates(classLoader, tight)).isInstanceOf(IllegalStateException.class).hasMessageContaining("max-input-chars");
    }

    /** 깨진 인코딩 · BOM 파일은 저장소 인코딩 규칙(UTF-8, BOM 없음)과 맞지 않아 커밋하지 않고 임시 디렉터리에 만든다. */
    @Test
    @DisplayName("UTF-8 이 아닌 파일은 실패하고, UTF-8 BOM 은 지운다")
    void encoding(@TempDir Path root) throws IOException {
        write(root, "broken-encoding", new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0xfa, 'x'});
        byte[] text = "문의 {{inquiry}}".getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[text.length + 3];
        bom[0] = (byte) 0xef;
        bom[1] = (byte) 0xbb;
        bom[2] = (byte) 0xbf;
        System.arraycopy(text, 0, bom, 3, text.length);
        write(root, "bom", bom);

        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> new PromptTemplates(loader, properties(Map.of("broken-encoding", new Feature(null, null, null, 1)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("UTF-8");
            assertThat(new PromptTemplates(loader, properties(Map.of("bom", new Feature(null, null, null, 1)))).get("bom").user()).isEqualTo("문의 {{inquiry}}");
        }
    }

    private static void write(Path root, String feature, byte[] bytes) throws IOException {
        Path dir = Files.createDirectories(root.resolve(PromptTemplates.ROOT).resolve(feature).resolve("v1"));
        Files.write(dir.resolve("user.txt"), bytes);
    }

    private static AiProperties properties(Map<String, Feature> features) {
        return new AiProperties(false, null, null, null, null, null, null, null, null, null, null, null, features, null, null, null, null);
    }
}
