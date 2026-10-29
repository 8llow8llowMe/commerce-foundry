package com.commercefoundry.domainlayer.member.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminOtpCredentialTest {

    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-29T00:00:00Z");

    private static AdminOtpCredential credential(long lastUsedTimeStep) {
        return new AdminOtpCredential(1L, 10L, new byte[] {1, 2, 3}, lastUsedTimeStep, CONFIRMED_AT);
    }

    @Test
    @DisplayName("등록 확인 — 호출자가 정한 아이디와 확인에 쓴 step 을 그대로 든다. 그 코드로 바로 로그인하지 못한다")
    void confirm() {
        AdminOtpCredential confirmed = AdminOtpCredential.confirm(7L, 10L, new byte[] {1, 2, 3}, 100L, CONFIRMED_AT);

        assertThat(confirmed.id()).isEqualTo(7L);
        assertThat(confirmed.lastUsedTimeStep()).isEqualTo(100L);
        assertThat(confirmed.accept(100L)).isEmpty();
    }

    @Test
    @DisplayName("마지막 step 보다 늦은 step 은 받고 그 step 을 기록한 새 값을 돌려준다 — 원래 값은 그대로다")
    void acceptsLaterStep() {
        AdminOtpCredential original = credential(100);

        AdminOtpCredential accepted = original.accept(101).orElseThrow();

        assertThat(accepted.lastUsedTimeStep()).isEqualTo(101);
        assertThat(accepted.secret()).isEqualTo(original.secret());
        assertThat(accepted.id()).isEqualTo(1L);
        assertThat(accepted.memberId()).isEqualTo(10L);
        assertThat(original.lastUsedTimeStep()).isEqualTo(100);
    }

    @Test
    @DisplayName("같은 step(같은 코드 재사용)이나 이전 step 은 거절한다")
    void rejectsSameOrEarlierStep() {
        assertThat(credential(100).accept(100)).isEmpty();
        assertThat(credential(100).accept(99)).isEmpty();
    }

    @Test
    @DisplayName("비밀은 복사해 들이고 복사해 내준다 — 밖에서 배열을 고쳐도 자격이 바뀌지 않는다")
    void copiesSecret() {
        byte[] secret = {1, 2, 3};
        AdminOtpCredential credential = new AdminOtpCredential(1L, 10L, secret, 0, CONFIRMED_AT);

        secret[0] = 9;
        credential.secret()[1] = 9;

        assertThat(credential.secret()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("같은 내용이면 같다(배열 내용 비교), toString 은 비밀을 가린다")
    void equalityAndMasking() {
        assertThat(credential(5)).isEqualTo(credential(5)).hasSameHashCodeAs(credential(5));
        assertThat(credential(5)).isNotEqualTo(credential(6));
        assertThat(credential(5).toString()).contains("secret=****").doesNotContain("[1, 2, 3]");
    }

    @Test
    @DisplayName("비밀이 비었거나 필수 칸이 없으면 만들지 않는다")
    void rejectsInvalid() {
        assertThatThrownBy(() -> new AdminOtpCredential(1L, 10L, new byte[0], 0, CONFIRMED_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminOtpCredential(1L, 10L, null, 0, CONFIRMED_AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminOtpCredential(1L, 10L, new byte[] {1}, 0, null)).isInstanceOf(NullPointerException.class);
    }
}
