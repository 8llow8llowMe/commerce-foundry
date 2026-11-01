package com.commercefoundry.domainlayer.order.adapter.in.admin.presenter;

/**
 * 어드민 주문 화면의 배송지 마스킹 (#17). 규칙은 auth-service {@code MemberMasking}(회원 이름 · 전화)과 맞춘다 — 운영자는 주문을 찾고 상태를 바꾸는 데
 * 원문이 필요 없다. 원문은 송장 출력(#18)처럼 원문이 꼭 필요한 경로가 감사와 함께 다룬다.
 *
 * <ul>
 *   <li>이름 — 첫 글자 · 마지막 글자만 (홍길동 → 홍*동, 두 글자는 첫 글자 + *, 한 글자는 *)</li>
 *   <li>연락처 — 앞 3자리 · 뒤 4자리만 (01012345678 → 010****5678). 7자리 미만이면 전부 가린다</li>
 *   <li>상세 주소 · 배송 요청 사항 — 있으면 통째로 가린다(공동현관 비밀번호처럼 자유 입력이 들어간다). 기본 주소 · 도시 · 주 · 우편번호는 지역 정보라 그대로 둔다</li>
 * </ul>
 */
final class OrderAdminMasking {

    static final String MASK = "****";

    private OrderAdminMasking() {
    }

    static String maskName(String name) {
        if (name == null) {
            return null;
        }
        int[] codePoints = name.strip().codePoints().toArray();
        return switch (codePoints.length) {
            case 0, 1 -> "*";
            case 2 -> Character.toString(codePoints[0]) + "*";
            default -> Character.toString(codePoints[0]) + "*" + Character.toString(codePoints[codePoints.length - 1]);
        };
    }

    static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        if (phone.length() < 7) {
            return MASK;
        }
        return phone.substring(0, 3) + MASK + phone.substring(phone.length() - 4);
    }

    /** 값이 있으면 통째로 가린다 — 비어 있으면 null 그대로(있었는지는 알린다). */
    static String maskIfPresent(String value) {
        return value == null || value.isBlank() ? null : MASK;
    }
}
