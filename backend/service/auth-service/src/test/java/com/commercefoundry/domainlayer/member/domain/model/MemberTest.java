package com.commercefoundry.domainlayer.member.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.security.principal.MemberRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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

    @ParameterizedTest
    @CsvSource({
        "CUSTOMER, false, false",
        "ADMIN,    true,  true",
        "OWNER,    true,  true",
        "MANAGER,  true,  false",
        "STAFF,    true,  false"
    })
    @DisplayName("관리자 로그인은 운영자 역할 전체가 쓰고, 대표 관리자는 기존 ADMIN · OWNER 다 — 고객 계정은 관리자 로그인 대상이 아니다 (#6 · #236)")
    void operatorAndOwnerRoles(MemberRole role, boolean operator, boolean owner) {
        Member member = Member.restore(1L, "a@example.com", HASH, "운영", null, role, 0L);

        assertThat(member.isOperator()).isEqualTo(operator);
        assertThat(member.isOwner()).isEqualTo(owner);
        assertThat(member.isActiveOperator()).isEqualTo(operator);
        assertThat(member.isCustomer()).isEqualTo(!operator);
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER", "MANAGER", "STAFF"})
    @DisplayName("운영자 역할 전체를 비활성화할 수 있고, 꺼진 운영자는 활성 운영자가 아니다 (#236)")
    void operatorsCanBeDeactivated(MemberRole role) {
        Member deactivated = Member.restore(1L, "a@example.com", HASH, "운영", null, role, 0L).deactivate(Instant.parse("2026-11-03T00:00:00Z"));

        assertThat(deactivated.isOperator()).isTrue();
        assertThat(deactivated.isActiveOperator()).isFalse();
    }

    @Test
    @DisplayName("마지막 활성 대표 관리자 판정 — 다른 활성 대표 관리자(ADMIN · OWNER)가 있어야 끌 수 있고, 매니저 · 스태프 · 꺼진 대표 관리자는 세지 않는다 (#236)")
    void lastActiveOwner() {
        Member admin = operator(1L, MemberRole.ADMIN);
        Member owner = operator(2L, MemberRole.OWNER);
        Member manager = operator(3L, MemberRole.MANAGER);
        Member staff = operator(4L, MemberRole.STAFF);
        Member formerOwner = operator(5L, MemberRole.OWNER).deactivate(Instant.parse("2026-11-03T00:00:00Z"));

        assertThat(Member.isLastActiveOwner(owner, List.of(owner, manager, staff, formerOwner))).isTrue();
        assertThat(Member.isLastActiveOwner(owner, List.of(admin, owner, manager))).isFalse();
        assertThat(Member.isLastActiveOwner(admin, List.of(admin, owner))).isFalse();
        assertThat(Member.isLastActiveOwner(manager, List.of(owner, manager))).isFalse();
        assertThat(Member.isLastActiveOwner(staff, List.of(staff))).isFalse();
    }

    @Test
    @DisplayName("대표 관리자 계정의 역할 · 상태는 활성 대표 관리자만 바꾼다 — 매니저 · 스태프 · 행위자 없음은 거절, 매니저 · 스태프 계정은 행위자를 따지지 않는다 (#236)")
    void canBeManagedBy() {
        Member owner = operator(2L, MemberRole.OWNER);
        Member manager = operator(3L, MemberRole.MANAGER);
        Member formerAdmin = operator(6L, MemberRole.ADMIN).deactivate(Instant.parse("2026-11-03T00:00:00Z"));

        assertThat(owner.canBeManagedBy(Optional.of(operator(1L, MemberRole.ADMIN)))).isTrue();
        assertThat(owner.canBeManagedBy(Optional.of(owner))).isTrue();
        assertThat(owner.canBeManagedBy(Optional.of(manager))).isFalse();
        assertThat(owner.canBeManagedBy(Optional.of(operator(4L, MemberRole.STAFF)))).isFalse();
        assertThat(owner.canBeManagedBy(Optional.of(formerAdmin))).isFalse();
        assertThat(owner.canBeManagedBy(Optional.empty())).isFalse();
        assertThat(manager.canBeManagedBy(Optional.empty())).isTrue();
    }

    @ParameterizedTest(name = "{0} 가 {1} 를 주면 {2}")
    @CsvSource({
        "ADMIN,   OWNER,   true",
        "OWNER,   OWNER,   true",
        "OWNER,   MANAGER, true",
        "OWNER,   STAFF,   true",
        "MANAGER, OWNER,   false",
        "MANAGER, MANAGER, true",
        "MANAGER, STAFF,   true",
        "STAFF,   OWNER,   false",
        "STAFF,   MANAGER, false",
        "STAFF,   STAFF,   true",
        "ADMIN,   ADMIN,   false",
        "OWNER,   ADMIN,   false",
        "OWNER,   CUSTOMER, false"
    })
    @DisplayName("줄 수 있는 역할 — 대표 관리자 역할은 대표 관리자만, 대표 관리자가 아니면 자기보다 높은 역할은 못 준다. ADMIN · CUSTOMER 는 누구도 못 준다 (#236)")
    void canAssignRole(MemberRole actorRole, MemberRole newRole, boolean allowed) {
        assertThat(Member.canAssignRole(Optional.of(operator(1L, actorRole)), newRole)).isEqualTo(allowed);
    }

    @Test
    @DisplayName("행위자가 없거나 꺼졌으면 어떤 역할도 줄 수 없다 (#236)")
    void inactiveActorCannotAssignRole() {
        Member formerOwner = operator(1L, MemberRole.OWNER).deactivate(Instant.parse("2026-11-03T00:00:00Z"));

        assertThat(Member.canAssignRole(Optional.of(formerOwner), MemberRole.STAFF)).isFalse();
        assertThat(Member.canAssignRole(Optional.empty(), MemberRole.STAFF)).isFalse();
    }

    @Test
    @DisplayName("역할 변경은 역할만 바꾸고 다른 값은 그대로다 — 같은 역할이면 같은 값 (#236)")
    void changeRole() {
        Member manager = operator(3L, MemberRole.MANAGER);

        assertThat(manager.changeRole(MemberRole.STAFF)).isEqualTo(Member.restore(3L, "3@example.com", HASH, "운영", null, MemberRole.STAFF, 0L));
        assertThat(manager.changeRole(MemberRole.MANAGER)).isEqualTo(manager);
    }

    @Test
    @DisplayName("고객의 역할은 바꾸지 않고, 운영자에게 고객 역할을 주지 않는다 — 호출 코드의 오류다 (#236)")
    void changeRoleRejectsCustomer() {
        Member customer = Member.restore(1L, "a@example.com", HASH, "홍길동", null, MemberRole.CUSTOMER, 0L);

        assertThatThrownBy(() -> customer.changeRole(MemberRole.STAFF)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operator(2L, MemberRole.STAFF).changeRole(MemberRole.CUSTOMER)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("역할 변경으로 이행용 ADMIN 을 주지 않는다 — ADMIN 에서 OWNER 로는 바꾼다 (#236)")
    void changeRoleRejectsAdmin() {
        assertThatThrownBy(() -> operator(2L, MemberRole.MANAGER).changeRole(MemberRole.ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operator(3L, MemberRole.OWNER).changeRole(MemberRole.ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThat(operator(4L, MemberRole.ADMIN).changeRole(MemberRole.OWNER).role()).isEqualTo(MemberRole.OWNER);
    }

    @Test
    @DisplayName("재활성화는 비활성화 시각만 지운다 — 비밀번호 해시는 그대로 (#236)")
    void reactivate() {
        Member former = operator(3L, MemberRole.MANAGER).deactivate(Instant.parse("2026-11-03T00:00:00Z"));

        Member reactivated = former.reactivate();

        assertThat(reactivated.isActiveOperator()).isTrue();
        assertThat(reactivated).isEqualTo(operator(3L, MemberRole.MANAGER));
        assertThatThrownBy(() -> Member.restore(1L, "a@example.com", HASH, "홍길동", null, MemberRole.CUSTOMER, 0L).reactivate())
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static Member operator(long id, MemberRole role) {
        return Member.restore(id, id + "@example.com", HASH, "운영", null, role, 0L);
    }

    @Test
    @DisplayName("고객은 비활성화하지 않는다 — 호출 코드의 오류다")
    void customerCannotBeDeactivated() {
        Member customer = Member.restore(1L, "a@example.com", HASH, "홍길동", null, MemberRole.CUSTOMER, 0L);

        assertThatThrownBy(() -> customer.deactivate(Instant.parse("2026-11-03T00:00:00Z"))).isInstanceOf(IllegalArgumentException.class);
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

        @ParameterizedTest
        @EnumSource(value = MemberRole.class, names = {"OWNER", "MANAGER", "STAFF"})
        @DisplayName("운영자 계정은 줄 수 있는 역할(OWNER · MANAGER · STAFF) 그대로 만들고 이메일 · 이름을 정규화한다 (#236)")
        void createsOperator(MemberRole role) {
            Member operator = Member.operator(1L, "  Staff@Example.COM ", HASH, " 직원 ", role);

            assertThat(operator).extracting(Member::email, Member::name, Member::phone, Member::role, Member::version, Member::deactivatedAt)
                .containsExactly("staff@example.com", "직원", null, role, null, null);
        }

        @ParameterizedTest
        @EnumSource(value = MemberRole.class, names = {"CUSTOMER", "ADMIN"})
        @NullSource
        @DisplayName("줄 수 있는 역할이 아니면 운영자 계정을 만들지 않는다 — 이행용 ADMIN 은 Member.admin() 으로만 만든다 (#236)")
        void operatorRejectsNonOperatorRole(MemberRole role) {
            assertThatThrownBy(() -> Member.operator(1L, "staff@example.com", HASH, "직원", role))
                .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
        }
    }
}
