package com.commercefoundry.domainlayer.member.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SignUpAgreementsTest {

    @ParameterizedTest(name = "이용약관={0}, 개인정보={1}")
    @CsvSource({"false, true", "true, false", "false, false"})
    @DisplayName("필수 동의(이용약관 · 개인정보) 중 하나라도 false 면 만들어지지 않는다")
    void rejectsMissingMandatory(boolean termsOfService, boolean privacyPolicy) {
        assertThatThrownBy(() -> new SignUpAgreements(termsOfService, privacyPolicy, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("필수 동의를 표시한다 — 마케팅만 선택이다")
    void requiredTypes() {
        assertThat(AgreementType.TERMS_OF_SERVICE.required()).isTrue();
        assertThat(AgreementType.PRIVACY_POLICY.required()).isTrue();
        assertThat(AgreementType.MARKETING.required()).isFalse();
    }

    @Test
    @DisplayName("이력은 3종 모두 만든다 — 마케팅 거절도 false 한 줄이고, 버전은 유형별 값이다")
    void historyHasAllThree() {
        AtomicLong ids = new AtomicLong(100);

        var history = new SignUpAgreements(true, true, false).toHistory(7L, ids::incrementAndGet, type -> "v-" + type.name());

        assertThat(history)
            .extracting(MemberAgreement::id, MemberAgreement::memberId, MemberAgreement::agreementType, MemberAgreement::agreed, MemberAgreement::termsVersion)
            .containsExactly(
                tuple(101L, 7L, AgreementType.TERMS_OF_SERVICE, true, "v-TERMS_OF_SERVICE"),
                tuple(102L, 7L, AgreementType.PRIVACY_POLICY, true, "v-PRIVACY_POLICY"),
                tuple(103L, 7L, AgreementType.MARKETING, false, "v-MARKETING")
            );
    }

    @Test
    @DisplayName("동의 이력 행은 약관 버전이 비면 만들어지지 않는다")
    void agreementRequiresVersion() {
        assertThatThrownBy(() -> new MemberAgreement(1L, 7L, AgreementType.MARKETING, true, " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
