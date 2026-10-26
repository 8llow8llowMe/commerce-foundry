package com.commercefoundry.domainlayer.member.adapter.in.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MemberMaskingTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "abcd@example.com     | ab***@e***.com",
        "abc@mail.co.kr       | ab***@m***.kr",
        "ab@example.com       | a***@e***.com",
        "a@example.com        | ***@e***.com",
        "abcd@localhost       | ab***@l***",
        "no-at-sign           | n***",
        "한글아이디@example.com | 한글***@e***.com"
    })
    @DisplayName("이메일은 아이디 앞 2자(2자면 1자, 1자면 0자) · 도메인 첫 글자 · 최상위 도메인만 남긴다")
    void email(String email, String masked) {
        assertThat(MemberMasking.maskEmail(email)).isEqualTo(masked);
    }

    @Test
    @DisplayName("빈 이메일 · null 은 *** 다")
    void emptyEmail() {
        assertThat(MemberMasking.maskEmail("")).isEqualTo("***");
        assertThat(MemberMasking.maskEmail(null)).isEqualTo("***");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "홍길동     | 홍*동",
        "남궁민수   | 남*수",
        "홍길       | 홍*",
        "홍         | *",
        "Kim Minsu  | K*u"
    })
    @DisplayName("이름은 첫 글자 · 마지막 글자만 남기고 별은 하나다(길이를 드러내지 않는다)")
    void name(String name, String masked) {
        assertThat(MemberMasking.maskName(name)).isEqualTo(masked);
    }

    @Test
    @DisplayName("표시 이름 칸은 이름이 없으면 null — maskName(null) 의 * 를 쓰지 않는다")
    void nameIfPresent() {
        assertThat(MemberMasking.maskNameIfPresent(null)).isNull();
        assertThat(MemberMasking.maskNameIfPresent("")).isNull();
        assertThat(MemberMasking.maskNameIfPresent("   ")).isNull();
        assertThat(MemberMasking.maskNameIfPresent("홍길동")).isEqualTo("홍*동");
    }

    @Test
    @DisplayName("이름은 코드 포인트로 센다 — 이모지가 반으로 잘리지 않는다")
    void nameByCodePoint() {
        assertThat(MemberMasking.maskName("😀길😀")).isEqualTo("😀*😀");
        assertThat(MemberMasking.maskName("")).isEqualTo("*");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "01012345678 | 010-****-5678",
        "0101234567  | 010-****-4567",
        "02-123      | ****"
    })
    @DisplayName("전화는 앞 3자리 · 끝 4자리만 남긴다. 7자 미만(이관 데이터)은 **** 다")
    void phone(String phone, String masked) {
        assertThat(MemberMasking.maskPhone(phone)).isEqualTo(masked);
    }

    @Test
    @DisplayName("전화가 없으면 null 그대로다")
    void noPhone() {
        assertThat(MemberMasking.maskPhone(null)).isNull();
    }
}
