package com.commercefoundry.domainlayer.member.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.security.principal.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MemberTest {

    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz012345";

    @Nested
    @DisplayName("회원가입")
    class SignUp {

        @Test
        @DisplayName("역할은 언제나 CUSTOMER, 버전은 null(새 회원)이다")
        void customerWithoutVersion() {
            Member member = Member.signUp(1L, "a@example.com", HASH, "홍길동", null);

            assertThat(member.role()).isEqualTo(MemberRole.CUSTOMER);
            assertThat(member.version()).isNull();
        }

        @Test
        @DisplayName("이메일은 앞뒤 공백을 지우고 소문자로, 이름은 앞뒤 공백을 지운다")
        void normalizesEmailAndName() {
            Member member = Member.signUp(1L, "  Customer@Example.COM ", HASH, "  홍길동 ", null);

            assertThat(member.email()).isEqualTo("customer@example.com");
            assertThat(member.name()).isEqualTo("홍길동");
        }

        @ParameterizedTest
        @ValueSource(strings = {"010-1234-5678", "01012345678", "010 1234 5678", "011-123-4567"})
        @DisplayName("전화번호는 하이픈 · 공백을 뺀 숫자만 저장한다")
        void normalizesPhone(String phone) {
            assertThat(Member.signUp(1L, "a@example.com", HASH, "홍길동", phone).phone()).matches("01[0-9]{8,9}");
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "  ", "-"})
        @DisplayName("전화번호가 없거나 비었으면 null 이다")
        void blankPhoneIsNull(String phone) {
            assertThat(Member.signUp(1L, "a@example.com", HASH, "홍길동", phone).phone()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"02-123-4567", "010-12-34", "+82-10-1234-5678", "0101234567890"})
        @DisplayName("국내 휴대폰 형식이 아니면 만들어지지 않는다")
        void rejectsInvalidPhone(String phone) {
            assertThatThrownBy(() -> Member.signUp(1L, "a@example.com", HASH, "홍길동", phone)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("정규화한 이름이 비었거나 50자를 넘으면 만들어지지 않는다")
        void rejectsInvalidName() {
            assertThatThrownBy(() -> Member.signUp(1L, "a@example.com", HASH, "   ", null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Member.signUp(1L, "a@example.com", HASH, "가".repeat(51), null)).isInstanceOf(IllegalArgumentException.class);
            assertThat(Member.signUp(1L, "a@example.com", HASH, " " + "가".repeat(50) + " ", null).name()).hasSize(50);
        }
    }

    @Nested
    @DisplayName("이메일 정규화")
    class NormalizeEmail {

        @Test
        @DisplayName("대소문자 · 앞뒤 공백만 다른 이메일은 같은 값이 된다 — 유니크 키가 중복을 막을 수 있다")
        void sameKey() {
            assertThat(Member.normalizeEmail(" A@Example.com")).isEqualTo(Member.normalizeEmail("a@example.COM "));
        }

        @Test
        @DisplayName("비었거나 254자를 넘으면 거절한다")
        void rejectsInvalidLength() {
            assertThatThrownBy(() -> Member.normalizeEmail("  ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Member.normalizeEmail("a".repeat(243) + "@example.com")).isInstanceOf(IllegalArgumentException.class);
            assertThat(Member.normalizeEmail("a".repeat(242) + "@example.com")).hasSize(254);
        }

        @Test
        @DisplayName("null 은 NullPointerException 이다 — 검증 없는 canonicalEmail 과 달리")
        void rejectsNull() {
            assertThatThrownBy(() -> Member.normalizeEmail(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("이메일 비교 형태(canonicalEmail)")
    class CanonicalEmail {

        @ParameterizedTest
        @ValueSource(strings = {"Member@Example.com ", " MEMBER@example.com", "member@example.com", "\tMember@EXAMPLE.COM\n"})
        @DisplayName("대소문자 · 앞뒤 공백만 다른 변형은 같은 값이 된다")
        void variantsCollapse(String email) {
            assertThat(Member.canonicalEmail(email)).isEqualTo("member@example.com");
        }

        @ParameterizedTest
        @ValueSource(strings = {"Member@Example.com ", " MEMBER@example.com", "member@example.com"})
        @DisplayName("멱등이다 — 이미 정규화한 값은 그대로 나온다")
        void idempotent(String email) {
            String once = Member.canonicalEmail(email);

            assertThat(Member.canonicalEmail(once)).isEqualTo(once);
        }

        @ParameterizedTest
        @ValueSource(strings = {"Member@Example.com ", " MEMBER@example.com", "member@example.com", " 회원@예시.한국 "})
        @DisplayName("규칙 안 값이면 normalizeEmail 과 같다 — normalizeEmail 은 canonicalEmail 에 길이 검증만 더한다")
        void sameAsNormalizeWithinRule(String email) {
            assertThat(Member.canonicalEmail(email)).isEqualTo(Member.normalizeEmail(email));
        }

        @Test
        @DisplayName("검증하지 않는다 — null 은 빈 문자열, 공백만이면 빈 문자열, 254자를 넘어도 그대로다(normalizeEmail 은 거절한다)")
        void doesNotValidate() {
            String tooLong = "A".repeat(243) + "@example.com";

            assertThat(Member.canonicalEmail(null)).isEmpty();
            assertThat(Member.canonicalEmail("   ")).isEmpty();
            assertThat(Member.canonicalEmail(tooLong)).isEqualTo("a".repeat(243) + "@example.com");
            assertThatThrownBy(() -> Member.normalizeEmail("   ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Member.normalizeEmail(tooLong)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("내 정보 변경")
    class ChangeProfile {

        private final Member member = new Member(1L, "a@example.com", HASH, "홍길동", "01012345678", MemberRole.CUSTOMER, 3L, null);

        @Test
        @DisplayName("null 인 칸은 그대로다 — 둘 다 null 이면 같은 값(equals)이다")
        void nullKeeps() {
            assertThat(member.changeProfile(null, null)).isEqualTo(member);
        }

        @Test
        @DisplayName("이름 · 전화번호를 정규화해 바꾸고, 이메일 · 해시 · 역할 · 버전은 그대로다")
        void changes() {
            Member changed = member.changeProfile(" 김철수 ", "010-9876-5432");

            assertThat(changed.name()).isEqualTo("김철수");
            assertThat(changed.phone()).isEqualTo("01098765432");
            assertThat(changed).extracting(Member::id, Member::email, Member::passwordHash, Member::role, Member::version)
                .containsExactly(1L, "a@example.com", HASH, MemberRole.CUSTOMER, 3L);
        }

        @Test
        @DisplayName("전화번호를 빈 문자열로 바꾸면 지운다")
        void emptyPhoneClears() {
            assertThat(member.changeProfile(null, "").phone()).isNull();
        }

        @Test
        @DisplayName("정규화하면 같은 값이면 바뀐 것이 없다")
        void normalizedSameIsUnchanged() {
            assertThat(member.changeProfile("홍길동 ", "010-1234-5678")).isEqualTo(member);
        }

        @Test
        @DisplayName("넘긴 칸이 규칙 밖이면 거절한다")
        void rejectsInvalidInput() {
            assertThatThrownBy(() -> member.changeProfile("   ", null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> member.changeProfile(null, "02-123-4567")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("저장된 행 복원")
    class Restore {

        /** 이관(#19) 데이터 — 유선 번호 · 51자 이름처럼 지금 규칙 밖인 값. */
        private final Member legacy = Member.restore(1L, "legacy@example.com", HASH, "가".repeat(51), "02-123-4567", MemberRole.CUSTOMER, 0L);

        @Test
        @DisplayName("규칙 밖 값도 검증 없이 그대로 되살린다 — 조회 · 로그인이 실패하지 않게")
        void keepsOutOfRuleValues() {
            assertThat(legacy.phone()).isEqualTo("02-123-4567");
            assertThat(legacy.name()).hasSize(51);
        }

        @Test
        @DisplayName("규칙은 바꾸는 칸에만 건다 — 이름만 바꾸면 규칙 밖 전화번호는 그대로 남는다")
        void validatesOnlyChangedFields() {
            Member changed = legacy.changeProfile("홍길동", null);

            assertThat(changed.name()).isEqualTo("홍길동");
            assertThat(changed.phone()).isEqualTo("02-123-4567");
        }
    }

    @Test
    @DisplayName("toString 에 비밀번호 해시 · 이메일 · 이름 · 전화번호를 싣지 않는다")
    void toStringHidesSecretsAndPersonalData() {
        Member member = new Member(7L, "secret@example.com", HASH, "홍길동", "01012345678", MemberRole.CUSTOMER, 0L, null);

        assertThat(member.toString()).contains("7").doesNotContain(HASH, "secret@example.com", "홍길동", "01012345678");
    }

    @Test
    @DisplayName("고객 로그인은 CUSTOMER 만 쓸 수 있다 — 관리자 계정은 고객 로그인 대상이 아니다")
    void onlyCustomerCanUseCustomerLogin() {
        assertThat(Member.restore(1L, "a@example.com", HASH, "홍길동", null, MemberRole.CUSTOMER, 0L).isCustomer()).isTrue();
        assertThat(Member.restore(1L, "a@example.com", HASH, "관리자", null, MemberRole.ADMIN, 0L).isCustomer()).isFalse();
    }

    @Test
    @DisplayName("관리자 로그인은 ADMIN 만 쓸 수 있다 — 고객 계정은 관리자 로그인 대상이 아니다 (#6)")
    void onlyAdminCanUseAdminLogin() {
        assertThat(Member.restore(1L, "a@example.com", HASH, "관리자", null, MemberRole.ADMIN, 0L).isAdmin()).isTrue();
        assertThat(Member.restore(1L, "a@example.com", HASH, "홍길동", null, MemberRole.CUSTOMER, 0L).isAdmin()).isFalse();
    }

    @Nested
    @DisplayName("관리자 계정 (#6)")
    class Admin {

        @Test
        @DisplayName("역할은 언제나 ADMIN, 전화 없음, 버전 null 이고 이메일 · 이름을 가입과 같이 정규화한다")
        void admin() {
            Member admin = Member.admin(1L, "  Admin@Example.COM ", HASH, " 관리자 ");

            assertThat(admin).extracting(Member::email, Member::name, Member::phone, Member::role, Member::version)
                .containsExactly("admin@example.com", "관리자", null, MemberRole.ADMIN, null);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "　"})
        @DisplayName("이름이 공백만이면(전각 공백 포함) 거절한다")
        void blankName(String name) {
            assertThatThrownBy(() -> Member.admin(1L, "admin@example.com", HASH, name)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
