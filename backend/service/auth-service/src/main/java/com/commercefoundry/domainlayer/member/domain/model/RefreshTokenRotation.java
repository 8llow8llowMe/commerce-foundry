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
    REUSE_DETECTED
}
