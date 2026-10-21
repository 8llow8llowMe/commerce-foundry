package com.commercefoundry.domainlayer.member.application.info;

/**
 * 초기 관리자 시드 결과 (#6). 기동을 멈추는 경우(설정 하나만 있음 · 비밀번호 규칙 위반)는 결과가 아니라 {@link IllegalStateException} 이다.
 */
public enum AdminSeedOutcome {

    /** 이메일 · 비밀번호가 모두 비었다 — 아무것도 하지 않는다. 운영은 첫 배포 뒤 비워 둔다. */
    NOT_CONFIGURED,

    /** 새 관리자 계정을 만들었다. */
    CREATED,

    /** 같은 이메일의 관리자가 이미 있다 — 비밀번호를 덮어쓰지 않는다(회전은 후속). */
    ALREADY_ADMIN,

    /** 같은 이메일의 고객이 있다 — 관리자로 승격하지 않는다. 다른 이메일을 쓰거나 사람이 확인한다. */
    CUSTOMER_EXISTS,

    /** 조회와 저장 사이에 같은 이메일이 먼저 저장됐다(유니크 키 충돌 — 보통 다른 인스턴스의 시드) — 건너뛴다. 다음 기동에서 관리자 · 고객 중 무엇인지 드러난다. */
    CREATED_CONCURRENTLY
}
