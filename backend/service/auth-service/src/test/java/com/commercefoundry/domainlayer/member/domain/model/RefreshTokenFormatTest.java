package com.commercefoundry.domainlayer.member.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.issuer.OpaqueTokenGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RefreshTokenFormatTest {

    @RepeatedTest(20)
    @DisplayName("발급기가 만든 토큰은 언제나 모양이 맞다")
    void generatedTokensAreWellFormed() {
        assertThat(RefreshTokenFormat.isWellFormed(new OpaqueTokenGenerator().generate())).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCg",
        "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCggA",
        "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg",
        "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCg=",
        "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwC g",
        "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCg\n"
    })
    @DisplayName("길이 · 문자 집합(+ / = 공백 줄바꿈)이 다르면 모양이 틀리다")
    void malformed(String value) {
        assertThat(RefreshTokenFormat.isWellFormed(value)).isFalse();
    }

    @Test
    @DisplayName("세션의 toString 은 지문 앞 8자만 싣는다")
    void sessionHidesFingerprint() {
        RefreshTokenSession session = new RefreshTokenSession("n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg", 1L, "family");

        assertThat(session.toString()).contains("n4bQgYhM…").doesNotContain("n4bQgYhMf");
    }
}
