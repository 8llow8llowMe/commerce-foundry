package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 회원 단위 직렬화. 다른 컨텍스트(배송지)의 Facade 가 회원 하나에 걸친 불변식(개수 상한 · 기본 배송지 하나)을 지키려고
 * 같은 트랜잭션 맨 앞에서 부른다 ({@code architecture-guide.md} §3 컨텍스트 간 의존 — Processor 만 Facade 에서).
 *
 * <p>잠금은 회원 행({@code member})에 건다. 회원 행이 그 회원의 쓰기를 모두 거치는 단 하나의 행이라, 아직 없는 배송지 행(생성)까지 함께 막을 수 있다.
 * 회원 버전은 올리지 않는다 — 내 정보 수정(낙관적 락)과는 잠금 대기만 겹치고 충돌(AUTH_004)은 나지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MemberLockProcessor {

    private final MemberRepositoryPort memberRepositoryPort;

    /**
     * 회원 행을 쓰기 잠금한다. 트랜잭션은 호출한 Facade 가 건다 — 잠금은 그 트랜잭션이 끝날 때 풀린다.
     *
     * @throws MemberException {@link MemberErrorCode#NOT_FOUND_MEMBER} — 토큰은 유효하지만 회원이 없다
     */
    public void lock(long memberId) {
        if (memberRepositoryPort.findByIdForUpdate(memberId).isEmpty()) {
            throw new MemberException(MemberErrorCode.NOT_FOUND_MEMBER);
        }
    }
}
