package com.commercefoundry.domainlayer.member.domain.model;

/**
 * refresh 토큰 회전 결과 ({@code security-guide.md} §2-3). 저장소가 한 번에(원자) 판정한다.
 */
public enum RefreshTokenRotation {

    /** 처음 쓰인 토큰이다. 회전 시각을 남기고 새 토큰을 같은 family 로 저장했다. */
    ROTATED,

    /**
     * 이미 회전한 토큰이 재사용 유예 안에 다시 왔다 — 동시 요청 · 응답 유실의 재시도로 본다. 새 토큰(형제)을 같은 family 로 저장했다.
     * 옛 토큰의 회전 시각은 그대로다 — 유예가 첫 회전부터 재는 창이라 재시도가 창을 늘리지 못한다.
     */
    REISSUED_IN_GRACE,

    /** 토큰이 없거나 · family 가 없거나(폐기 · 만료) · 읽은 뒤 바뀌었다. 저장소는 바꾸지 않았다. */
    INVALID,

    /** 이미 회전한 토큰이 유예 밖에서 다시 왔다 — 탈취로 보고 family 를 지웠다. 형제 토큰도 함께 무효다. */
    REUSE_DETECTED,

    /** family 가 절대 수명(최초 로그인부터)을 다 썼다 — 회전하지 않고 family 를 지웠다. 탈취 신호가 아니라 정상 만료다(재로그인). */
    FAMILY_EXPIRED,

    /**
     * family 값(로그인 시각)이 손상됐다 — 정수 문자열이 아니거나 · 무한대로 읽히거나 · 지금 + 허용 시계 오차({@link RefreshTokenRotationPolicy#CLOCK_SKEW})보다 미래다.
     * 회전하지 않고 family 를 지웠다. 정상 흐름에는 없다 — 저장소를 직접 고쳤거나 시계가 크게 앞선 인스턴스가 로그인을 받은 신호라 만료(info)와 나눠 남긴다.
     * 받아 주면 절대 수명 상한이 사실상 사라진다.
     */
    FAMILY_CORRUPTED;

    /** 새 토큰을 저장한 결과(회전 · 유예 안 형제 발급)인가. */
    public boolean issued() {
        return this == ROTATED || this == REISSUED_IN_GRACE;
    }
}
