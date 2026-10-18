package com.commercefoundry.domainlayer.cart.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomOptionsTest {

    @Test
    @DisplayName("입력이 없으면 해시는 빈 문자열이다 — 유니크 키에서 null 은 중복을 막지 못한다")
    void emptyHashIsEmptyString() {
        assertThat(CustomOptions.empty().hash()).isEmpty();
        assertThat(CustomOptions.of(null).hash()).isEmpty();
        assertThat(CustomOptions.of(List.of()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("해시는 SHA-256 소문자 hex 64자이고, 같은 입력이면 실행마다 같다(고정값)")
    void hashIsStable() {
        CustomOptions options = CustomOptions.of(List.of(CustomOption.of("chest", "96.5"), CustomOption.of("memo", "소매 길게")));

        assertThat(options.hash()).hasSize(64).matches("[0-9a-f]{64}");
        // 정규형(길이 접두 · fieldKey 정렬 · UTF-8)이 바뀌면 이미 저장된 줄과 같은 입력이 다른 줄이 된다. 바꾸려면 저장된 해시를 다시 계산하는 이관이 필요하다
        assertThat(options.hash()).isEqualTo(CustomOptions.of(List.of(CustomOption.of("chest", "96.5"), CustomOption.of("memo", "소매 길게"))).hash());
        assertThat(options.hash()).isEqualTo(sha256Hex("5:chest4:96.54:memo5:소매 길게"));
    }

    @Test
    @DisplayName("순서만 다르거나 값의 앞뒤 공백만 다르면 같은 입력이다")
    void normalization() {
        CustomOptions original = CustomOptions.of(List.of(CustomOption.of("chest", "96.5"), CustomOption.of("waist", "80")));
        CustomOptions reordered = CustomOptions.of(List.of(CustomOption.of("waist", " 80 "), CustomOption.of("chest", "96.5\n")));

        assertThat(reordered.hash()).isEqualTo(original.hash());
        assertThat(reordered.sameAs(original)).isTrue();
        // 표시 순서는 받은 순서다
        assertThat(reordered.values()).extracting(CustomOption::fieldKey).containsExactly("waist", "chest");
        assertThat(reordered.values()).extracting(CustomOption::value).containsExactly("80", "96.5");
    }

    @Test
    @DisplayName("NFD(자모 분리)로 온 한글도 NFC 로 저장하고 같은 해시다 — macOS 등에서 온 입력이 다른 줄이 되지 않는다")
    void unicodeNormalization() {
        String nfc = "소매 길게";
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo(nfc);

        CustomOption fromNfd = CustomOption.of("memo", " " + nfd + " ");

        assertThat(fromNfd.value()).isEqualTo(nfc);
        assertThat(CustomOptions.of(List.of(fromNfd)).hash()).isEqualTo(CustomOptions.of(List.of(CustomOption.of("memo", nfc))).hash());
    }

    @Test
    @DisplayName("값이 다르거나 키 경계가 다르면 다른 입력이다 — 길이 접두로 a=bc 와 ab=c 가 겹치지 않는다")
    void differentInputsDiffer() {
        assertThat(CustomOptions.of(List.of(CustomOption.of("chest", "96"))).hash())
            .isNotEqualTo(CustomOptions.of(List.of(CustomOption.of("chest", "96.5"))).hash());
        assertThat(CustomOptions.of(List.of(CustomOption.of("a", "bc"))).hash())
            .isNotEqualTo(CustomOptions.of(List.of(CustomOption.of("ab", "c"))).hash());
        assertThat(CustomOptions.of(List.of(CustomOption.of("a", "b"), CustomOption.of("c", "d"))).hash())
            .isNotEqualTo(CustomOptions.of(List.of(CustomOption.of("a", "b4:c1:d"))).hash());
        assertThat(CustomOptions.of(List.of(CustomOption.of("chest", "96"))).hash()).isNotEqualTo(CustomOptions.empty().hash());
    }

    @Test
    @DisplayName("fieldKey 중복 · 20개 초과는 만들 수 없다 (요청 검증이 먼저 거른다)")
    void rejectsInvalid() {
        assertThatThrownBy(() -> CustomOptions.of(List.of(CustomOption.of("chest", "1"), CustomOption.of("chest", "2"))))
            .isInstanceOf(IllegalArgumentException.class);

        List<CustomOption> tooMany = new ArrayList<>();
        IntStream.rangeClosed(1, CustomOptions.MAX_SIZE + 1).forEach(index -> tooMany.add(CustomOption.of("f" + index, "v")));
        assertThatThrownBy(() -> CustomOptions.of(tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThat(CustomOptions.of(tooMany.subList(0, CustomOptions.MAX_SIZE)).values()).hasSize(CustomOptions.MAX_SIZE);
    }

    @Test
    @DisplayName("아이디는 비어 있는 칸만 채우고, 해시에 들지 않는다")
    void withIds() {
        CustomOptions options = CustomOptions.of(List.of(CustomOption.of("chest", "96"), new CustomOption(7L, "memo", "x")));
        AtomicLong sequence = new AtomicLong(100);

        CustomOptions withIds = options.withIds(sequence::incrementAndGet);

        assertThat(withIds.values()).extracting(CustomOption::id).containsExactly(101L, 7L);
        assertThat(withIds.hash()).isEqualTo(options.hash());
    }

    private static String sha256Hex(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
