package com.commercefoundry.domainlayer.order.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderCustomOptionsTest {

    @Test
    @DisplayName("입력이 없으면 해시는 빈 문자열이다")
    void emptyHashIsEmptyString() {
        assertThat(OrderCustomOptions.empty().hash()).isEmpty();
        assertThat(OrderCustomOptions.of(null).hash()).isEmpty();
        assertThat(OrderCustomOptions.of(List.of()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("해시는 장바구니와 같은 정규형(길이 접두 · fieldKey 정렬 · UTF-8)의 SHA-256 이다 — 장바구니 CustomOptionsTest 와 같은 고정값을 붙잡는다")
    void hashMatchesCartRule() {
        OrderCustomOptions options = OrderCustomOptions.of(List.of(new OrderCustomOption("memo", "소매 길게"), new OrderCustomOption("chest", "96.5")));

        assertThat(options.hash()).hasSize(64).matches("[0-9a-f]{64}");
        // 정규형이 장바구니(cart CustomOptions)와 갈라지면 같은 입력이 장바구니와 주문에서 다른 줄이 된다. 바꾸려면 둘을 같이 바꾼다
        assertThat(options.hash()).isEqualTo(sha256Hex("5:chest4:96.54:memo5:소매 길게"));
    }

    @Test
    @DisplayName("순서만 다르거나 키 · 값의 앞뒤 공백만 다르면 같은 해시다 — 표시 순서는 받은 순서다")
    void orderAndWhitespaceIndependent() {
        OrderCustomOptions original = OrderCustomOptions.of(List.of(new OrderCustomOption("chest", "96.5"), new OrderCustomOption("waist", "80")));
        OrderCustomOptions reordered = OrderCustomOptions.of(List.of(new OrderCustomOption(" waist", " 80 "), new OrderCustomOption("chest ", "96.5\n")));

        assertThat(reordered.hash()).isEqualTo(original.hash());
        assertThat(reordered.values()).extracting(OrderCustomOption::fieldKey).containsExactly("waist", "chest");
        assertThat(reordered.values()).extracting(OrderCustomOption::value).containsExactly("80", "96.5");
    }

    @Test
    @DisplayName("NFD(자모 분리)로 온 한글도 NFC 로 바꾸고 같은 해시다")
    void unicodeNormalization() {
        String nfc = "소매 길게";
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo(nfc);

        OrderCustomOption fromNfd = new OrderCustomOption("memo", " " + nfd + " ");

        assertThat(fromNfd.value()).isEqualTo(nfc);
        assertThat(OrderCustomOptions.of(List.of(fromNfd)).hash()).isEqualTo(OrderCustomOptions.of(List.of(new OrderCustomOption("memo", nfc))).hash());
    }

    @Test
    @DisplayName("값이 다르거나 키 경계가 다르면 다른 해시다")
    void differentInputsDiffer() {
        assertThat(OrderCustomOptions.of(List.of(new OrderCustomOption("chest", "96"))).hash())
            .isNotEqualTo(OrderCustomOptions.of(List.of(new OrderCustomOption("chest", "96.5"))).hash());
        assertThat(OrderCustomOptions.of(List.of(new OrderCustomOption("a", "bc"))).hash())
            .isNotEqualTo(OrderCustomOptions.of(List.of(new OrderCustomOption("ab", "c"))).hash());
        assertThat(OrderCustomOptions.of(List.of(new OrderCustomOption("chest", "96"))).hash()).isNotEqualTo(OrderCustomOptions.empty().hash());
    }

    @Test
    @DisplayName("fieldKey 중복(정규화 후 포함) · 20개 초과 · null 칸은 만들 수 없다 (요청 검증이 먼저 거른다)")
    void rejectsInvalid() {
        assertThatThrownBy(() -> OrderCustomOptions.of(List.of(new OrderCustomOption("chest", "1"), new OrderCustomOption(" chest", "2"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCustomOption(null, "1")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderCustomOption("chest", null)).isInstanceOf(NullPointerException.class);

        List<OrderCustomOption> tooMany = new ArrayList<>();
        IntStream.rangeClosed(1, OrderCustomOptions.MAX_SIZE + 1).forEach(index -> tooMany.add(new OrderCustomOption("f" + index, "v")));
        assertThatThrownBy(() -> OrderCustomOptions.of(tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThat(OrderCustomOptions.of(tooMany.subList(0, OrderCustomOptions.MAX_SIZE)).values()).hasSize(OrderCustomOptions.MAX_SIZE);
    }

    @Test
    @DisplayName("값은 HTML 을 거르지 않은 원문이고, toString 은 값을 가린다 — 치수 같은 개인 정보를 로그에 남기지 않는다")
    void rawValueMaskedInToString() {
        OrderCustomOption option = new OrderCustomOption("memo", "<b>96</b>");

        assertThat(option.value()).isEqualTo("<b>96</b>");
        assertThat(OrderCustomOptions.of(List.of(option)).toString()).contains("fieldKey=memo").doesNotContain("96");
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
