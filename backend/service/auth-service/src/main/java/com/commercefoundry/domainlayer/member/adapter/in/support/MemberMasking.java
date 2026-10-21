package com.commercefoundry.domainlayer.member.adapter.in.support;

/**
 * 회원 개인정보 마스킹 (#6) — 어드민 응답({@code MemberAdminPresenter})과 기동 로그({@code AdminSeedRunner})가 같이 쓴다. 규칙의 정본은
 * {@code api-design-guide.md} §2-4 "어드민 회원 조회" 다. 글자 수는 코드 포인트로 센다(한글 · 이모지가 반으로 잘리지 않게).
 *
 * <ul>
 *   <li>이메일 {@code abcd@example.com → ab***@e***.com} — 아이디는 3자 이상이면 앞 2자, 2자면 앞 1자, 1자면 남기지 않고 + {@code ***}.
 *       도메인은 마지막 점 앞을 첫 글자 + {@code ***} 로 가리고 마지막 점부터(최상위 도메인)는 남긴다. 점이 없으면 첫 글자 + {@code ***}.
 *       {@code @} 가 없으면(이관 데이터) 앞 1자 + {@code ***}</li>
 *   <li>이름 {@code 홍길동 → 홍*동} — 3자 이상은 첫 글자 + {@code *} + 마지막 글자(길이를 드러내지 않게 별은 하나), 2자는 첫 글자 + {@code *}, 1자 이하는 {@code *}</li>
 *   <li>전화 {@code 01012345678 → 010-****-5678} — 숫자만 저장된 값의 앞 3자리 + {@code -****-} + 끝 4자리. 7자 미만(이관 데이터)은 {@code ****}, 없으면 null</li>
 * </ul>
 */
public final class MemberMasking {

    private static final String MASK = "***";

    private MemberMasking() {
    }

    public static String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return MASK;
        }
        int at = email.lastIndexOf('@');
        if (at < 0) {
            return keepHead(email, 1) + MASK;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        int length = local.codePointCount(0, local.length());
        int keep = length > 2 ? 2 : length - 1;
        return keepHead(local, keep) + MASK + "@" + maskDomain(domain);
    }

    public static String maskName(String name) {
        if (name == null) {
            return "*";
        }
        int[] codePoints = name.codePoints().toArray();
        return switch (codePoints.length) {
            case 0, 1 -> "*";
            case 2 -> Character.toString(codePoints[0]) + "*";
            default -> Character.toString(codePoints[0]) + "*" + Character.toString(codePoints[codePoints.length - 1]);
        };
    }

    public static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        if (phone.length() < 7) {
            return "****";
        }
        return phone.substring(0, 3) + "-****-" + phone.substring(phone.length() - 4);
    }

    private static String maskDomain(String domain) {
        int lastDot = domain.lastIndexOf('.');
        if (lastDot <= 0) {
            return keepHead(domain, 1) + MASK;
        }
        return keepHead(domain, 1) + MASK + domain.substring(lastDot);
    }

    /** 앞 {@code count} 코드 포인트. 빈 문자열이거나 0 이하면 빈 문자열이다. */
    private static String keepHead(String value, int count) {
        if (value.isEmpty() || count <= 0) {
            return "";
        }
        int end = value.offsetByCodePoints(0, Math.min(count, value.codePointCount(0, value.length())));
        return value.substring(0, end);
    }
}
