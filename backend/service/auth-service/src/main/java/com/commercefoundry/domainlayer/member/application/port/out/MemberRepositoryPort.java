package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import java.util.Optional;

/**
 * 회원. 이메일은 정규화한 값({@code Member.normalizeEmail})으로 넘긴다.
 */
public interface MemberRepositoryPort {

    boolean existsByEmail(String email);

    /** 로그인 — 고객 · 관리자를 가리지 않고 찾는다. 역할 판정은 부르는 쪽이 한다. */
    Optional<Member> findByEmail(String email);

    Optional<Member> findById(long memberId);

    /**
     * 새 회원(버전 null)이면 넣고, 아니면 그 버전에 대한 수정으로 저장한다. 바로 flush 해 제약 위반 · 버전 충돌을 여기서 드러낸다.
     *
     * @throws MemberException {@code DUPLICATE_EMAIL} — 이메일 유니크 키 위반(동시 가입). {@code MEMBER_UPDATE_CONFLICT} — 다른 요청이 먼저 고쳤다
     */
    Member save(Member member);

    /**
     * 회원 행은 그대로 두고 버전만 올린다 — 회원 행 밖의 회원 상태(마케팅 동의 이력)를 바꿀 때 동시 수정을 회원 버전 하나로 직렬화한다.
     * 트랜잭션 안에서 부른다.
     *
     * @throws MemberException {@code MEMBER_UPDATE_CONFLICT} — 읽은 버전 뒤에 다른 요청이 먼저 고쳤다
     */
    void incrementVersion(Member member);
}
