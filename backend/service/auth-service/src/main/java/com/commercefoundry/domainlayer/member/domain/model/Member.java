package com.commercefoundry.domainlayer.member.domain.model;

import com.commercefoundry.security.principal.MemberRole;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 회원 — 고객과 관리자 계정이 같은 테이블이고 {@link #role} 로 나뉜다 (#4 결정 4, 관리자 발급은 #6).
 *
 * <p><b>검증은 쓰는 쪽에서만 한다.</b> 가입({@link #signUp}) · 수정({@link #changeProfile})이 넘겨받은 값을 정규화하고 규칙을 검사한다 —
 * 이메일은 앞뒤 공백 제거 + 소문자, 이름은 앞뒤 공백 제거, 전화번호는 하이픈 · 공백을 뺀 숫자만. 규칙을 벗어나면 {@link IllegalArgumentException} 이다
 * (요청 검증 AUTH_1xx 를 지나온 값이라 여기서 걸리면 호출 코드의 오류다). 저장된 행은 {@link #restore} 로 검증 없이 되살린다 —
 * 이관(#19) 데이터가 지금 규칙 밖이어도 조회 · 로그인이 500 이 되지 않게. 캐노니컬 생성자도 null 만 본다.
 *
 * <p>역할은 security-core {@link MemberRole} 을 그대로 쓴다 — 저장한 값이 곧 access 토큰 {@code role} claim 이라 두 enum 이 갈라지지 않게.
 *
 * <p>상태는 메서드가 새 값을 돌려주는 방식으로만 바뀐다. setter 를 두지 않는다.
 *
 * @param passwordHash BCrypt 해시. 원문은 어디에도 두지 않는다 — {@link #toString()} 에도 싣지 않는다
 * @param phone 숫자만({@code 01012345678}). 없으면 null
 * @param version 낙관적 락 버전. 아직 저장하지 않은 회원은 null
 * @param deactivatedAt 운영자 비활성화 시각 (#193). 고객은 null. null 이면 활성 운영자다
 */
public record Member(long id, String email, String passwordHash, String name, String phone, MemberRole role, Long version, Instant deactivatedAt) {

    public static final int EMAIL_MAX_LENGTH = 254;
    public static final int NAME_MAX_LENGTH = 50;

    /** 국내 휴대폰 번호 — 숫자만 10~11자리. 해외 번호는 영문 스토어 회원가입 이슈에서 넓힌다. */
    private static final Pattern PHONE_DIGITS = Pattern.compile("01[0-9]{8,9}");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s-]");

    public Member {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
    }

    /** 고객 회원가입. 값을 정규화 · 검증한다. 역할은 언제나 {@link MemberRole#CUSTOMER} 다 — 요청으로 역할을 받지 않는다. */
    public static Member signUp(long id, String email, String passwordHash, String name, String phone) {
        return new Member(id, normalizeEmail(email), passwordHash, normalizeName(name), normalizePhone(phone), MemberRole.CUSTOMER, null, null);
    }

    /**
     * 관리자 계정 — 초기 시드(#6)와 가게 운영자 추가(#193)가 쓴다. 이메일 · 이름을 가입과 같은 규칙으로 정규화 · 검증하고, 역할은 언제나 {@link MemberRole#ADMIN} 이다 —
     * 어드민 화면이 아직 ADMIN 으로 세션을 판정해 OWNER · MANAGER · STAFF 발급은 화면 전환(#237)과 함께 연다 (#236).
     * 전화번호는 없다(null). 고객 가입({@link #signUp})과 달리 동의 이력을 남기지 않는다 — 관리자는 이용약관 동의 대상이 아니다.
     */
    public static Member admin(long id, String email, String passwordHash, String name) {
        return new Member(id, normalizeEmail(email), passwordHash, normalizeName(name), null, MemberRole.ADMIN, null, null);
    }

    /** 저장된 행을 검증 없이 되살린다 — 영속성 매퍼만 쓴다. 규칙 밖 값(이관 데이터)도 그대로 둔다. 비활성화 시각은 없다. */
    public static Member restore(long id, String email, String passwordHash, String name, String phone, MemberRole role, Long version) {
        return restore(id, email, passwordHash, name, phone, role, version, null);
    }

    /** 저장된 행을 검증 없이 되살린다. {@code deactivatedAt} 이 있으면 비활성 관리자다 (#193). */
    public static Member restore(
        long id, String email, String passwordHash, String name, String phone, MemberRole role, Long version, Instant deactivatedAt
    ) {
        return new Member(id, email, passwordHash, name, phone, role, version, deactivatedAt);
    }

    /**
     * 이름 · 전화번호를 바꾼 회원. 넘겨받은 칸만 정규화 · 검증하고, 넘기지 않은 칸은 저장된 값 그대로다. 바뀐 것이 없으면 같은 값({@code equals})이다.
     *
     * @param newName null 이면 그대로
     * @param newPhone null 이면 그대로, 빈 문자열이면 지운다. 공백만 있는 값은 요청 검증이 AUTH_109 로 거절한다
     */
    public Member changeProfile(String newName, String newPhone) {
        String changedName = newName == null ? name : normalizeName(newName);
        String changedPhone = newPhone == null ? phone : normalizePhone(newPhone);
        return new Member(id, email, passwordHash, changedName, changedPhone, role, version, deactivatedAt);
    }

    /** 비밀번호 해시를 바꾼 회원. 원문은 받지 않는다. */
    public Member changePassword(String newPasswordHash) {
        Objects.requireNonNull(newPasswordHash, "passwordHash");
        return new Member(id, email, newPasswordHash, name, phone, role, version, deactivatedAt);
    }

    /**
     * 비활성화한 운영자 계정. 운영자 역할 전체({@link MemberRole#OPERATOR_ROLES})가 대상이다. 고객에게 부르면 호출 코드의 오류다.
     *
     * @throws IllegalArgumentException 운영자가 아니거나 시각이 없다
     */
    public Member deactivate(Instant at) {
        Objects.requireNonNull(at, "deactivatedAt");
        if (!isOperator()) {
            throw new IllegalArgumentException("운영자 계정만 비활성화한다");
        }
        return new Member(id, email, passwordHash, name, phone, role, version, at);
    }

    /** 고객 로그인 · 토큰 재발급을 쓸 수 있는 계정인가. 관리자 계정은 고객 로그인으로 들어오지 못한다 (#4 결정 4, 관리자 로그인은 #6). */
    public boolean isCustomer() {
        return role == MemberRole.CUSTOMER;
    }

    /**
     * 운영자 계정인가 — 기존 ADMIN · OWNER · MANAGER · STAFF ({@link MemberRole#isOperator}, #236). 관리자 로그인(#6)을 쓸 수 있는 역할이고,
     * 고객 계정은 관리자 로그인으로 들어오지 못한다 — {@link #isCustomer} 의 반대편이다. 무엇을 할 수 있는지는 역할별 기능 권한이 정한다.
     */
    public boolean isOperator() {
        return role.isOperator();
    }

    /** 대표 관리자(소유자) 계정인가 — 기존 ADMIN · OWNER ({@link MemberRole#isOwner}). 마지막 관리자 가드 · 시스템 알림 수신자의 기준이다 (#236). */
    public boolean isOwner() {
        return role.isOwner();
    }

    /** 관리자 로그인 · 어드민 API 를 아직 쓸 수 있는 운영자 계정인가. 비활성화(#193)된 운영자는 아니다. */
    public boolean isActiveOperator() {
        return isOperator() && deactivatedAt == null;
    }

    /**
     * {@code target} 이 활성 대표 관리자 중 마지막 한 명인가 — 끄면 운영자 계정을 관리할 사람이 남지 않는다 (#193 · #236).
     * 매니저 · 스태프는 대표 관리자가 아니라 언제나 false 다. 대표 관리자 수는 {@code activeOperators} 중 활성 대표 관리자만 센다.
     *
     * @param activeOperators 같은 트랜잭션에서 잠가 읽은 활성 운영자 전부
     */
    public static boolean isLastActiveOwner(Member target, Collection<Member> activeOperators) {
        return target.isOwner() && activeOperators.stream()
            .noneMatch(other -> other.id() != target.id() && other.isOwner() && other.isActiveOperator());
    }

    /**
     * 이 운영자 계정을 {@code actor} 가 끌 수 있는가. 대표 관리자 계정은 활성 대표 관리자만 끈다 — 매니저 · 스태프가 직원 관리 권한을 받게 되더라도
     * 대표 관리자를 내보내지 못하게 (#236). 매니저 · 스태프 계정은 행위자를 따지지 않는다(권한 판정은 메서드 보안이 한다).
     *
     * @param actor 행위자. 활성 운영자가 아니면(없음 · 비활성) 빈 값
     */
    public boolean canBeDeactivatedBy(Optional<Member> actor) {
        return !isOwner() || actor.filter(Member::isActiveOperator).filter(Member::isOwner).isPresent();
    }

    /**
     * 저장 · 조회 키로 쓰는 이메일 — {@link #canonicalEmail} 에 길이 검증을 더한다. 로그인(#4 A3)도 이 함수로 찾는다.
     *
     * @throws IllegalArgumentException 비었거나 {@value #EMAIL_MAX_LENGTH}자를 넘는다
     */
    public static String normalizeEmail(String email) {
        Objects.requireNonNull(email, "email");
        String normalized = canonicalEmail(email);
        if (normalized.isEmpty() || normalized.length() > EMAIL_MAX_LENGTH) {
            throw new IllegalArgumentException("email 길이가 규칙을 벗어났다");
        }
        return normalized;
    }

    /**
     * 같은 계정이면 같은 값이 되는 이메일 형태 — 앞뒤 공백 제거 + 소문자({@link Locale#ROOT}). 검증하지 않는 순수 함수이고 null 은 빈 문자열이다.
     * 멱등이라 이미 정규화한 값은 그대로 나온다. 거절하면 안 되는 쪽(시도 계수기 #66 — 카운터 키가 DB 조회 키와 같아야 한다)이 쓰고,
     * 저장 · 조회 키는 검증까지 하는 {@link #normalizeEmail} 로 만든다.
     */
    public static String canonicalEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizeName(String name) {
        Objects.requireNonNull(name, "name");
        String normalized = name.strip();
        if (normalized.isEmpty() || normalized.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("name 길이가 규칙을 벗어났다");
        }
        return normalized;
    }

    /** 하이픈 · 공백을 뺀 숫자만. null · 빈 값은 "번호 없음"(null)이다. */
    private static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = PHONE_SEPARATORS.matcher(phone).replaceAll("");
        if (digits.isEmpty()) {
            return null;
        }
        if (!PHONE_DIGITS.matcher(digits).matches()) {
            throw new IllegalArgumentException("phone 형식이 규칙을 벗어났다");
        }
        return digits;
    }

    /** 비밀번호 해시 · 개인정보(이메일 · 이름 · 전화)를 싣지 않는다 — 로그 · 예외 메시지로 새지 않게 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "Member[id=" + id + ", role=" + role + ", version=" + version + ", deactivatedAt=" + deactivatedAt + "]";
    }
}
