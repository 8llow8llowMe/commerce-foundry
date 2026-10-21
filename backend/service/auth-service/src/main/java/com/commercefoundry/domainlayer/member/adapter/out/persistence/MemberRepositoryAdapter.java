package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.MemberRepository;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.mapper.MemberMapper;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.query.MemberListQueryResult;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberRepositoryAdapter implements MemberRepositoryPort {

    /** 아이디 내림차순 하나 — Snowflake 라 생성 순서이고 유일해 동률이 없다. 기본 키 인덱스를 거꾸로 읽어 정렬용 인덱스가 따로 필요 없다. */
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("id"));

    private final MemberRepository memberRepository;
    private final MemberMapper memberMapper;

    @Override
    public boolean existsByEmail(String email) {
        return memberRepository.existsByEmail(email);
    }

    @Override
    public Optional<Member> findByEmail(String email) {
        return memberRepository.findByEmail(email).map(memberMapper::toDomain);
    }

    @Override
    public Optional<Member> findById(long memberId) {
        return memberRepository.findById(memberId).map(memberMapper::toDomain);
    }

    @Override
    public Optional<Member> findByIdForUpdate(long memberId) {
        return memberRepository.findWithLockById(memberId).map(memberMapper::toDomain);
    }

    /**
     * 최신순(아이디 내림차순) — 유일한 키라 페이지 경계에서 같은 회원이 두 번 나오거나 빠지지 않는다 ({@code api-design-guide.md} §4).
     * 회원 한 행이 곧 한 항목이라 연관 조회가 없다(N+1 없음). 질의는 목록 한 번 · count 한 번이다(첫 페이지가 다 차지 않으면 Spring Data 가 count 를 건너뛴다).
     * 역할 조건에는 인덱스가 없다 — 회원 표 크기(수만 행)에서는 기본 키 역순 스캔 + 필터로 충분하다. 커지면 {@code (role, id)} 인덱스를 더한다.
     */
    @Override
    public Page<MemberListQueryResult> findPage(MemberRole role, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, LATEST_FIRST);
        Page<MemberEntity> rows = role == null ? memberRepository.findAll(pageable) : memberRepository.findAllByRole(role, pageable);
        return rows.map(MemberRepositoryAdapter::toListRow);
    }

    private static MemberListQueryResult toListRow(MemberEntity entity) {
        return new MemberListQueryResult(entity.getId(), entity.getEmail(), entity.getName(), entity.getPhone(), entity.getRole(), entity.getCreatedAt());
    }

    /**
     * {@code saveAndFlush} 로 바로 내보낸다 — 커밋 때 터지면 예외가 Facade 밖(트랜잭션 프록시)에서 나 도메인 오류로 바꿀 자리가 없다.
     * 예외가 나면 트랜잭션은 롤백 전용이 되고, 여기서 던진 도메인 예외가 그대로 롤백시킨다.
     */
    @Override
    public Member save(Member member) {
        try {
            return memberMapper.toDomain(memberRepository.saveAndFlush(memberMapper.toEntity(member)));
        } catch (OptimisticLockingFailureException exception) {
            throw new MemberException(MemberErrorCode.MEMBER_UPDATE_CONFLICT, exception);
        } catch (DataIntegrityViolationException exception) {
            if (violates(exception, MemberEntity.UK_EMAIL)) {
                throw new MemberException(MemberErrorCode.DUPLICATE_EMAIL, exception);
            }
            throw exception;
        }
    }

    @Override
    public void incrementVersion(Member member) {
        Objects.requireNonNull(member.version(), "저장하지 않은 회원의 버전은 올릴 수 없다");
        if (memberRepository.incrementVersion(member.id(), member.version(), Instant.now()) == 0) {
            throw new MemberException(MemberErrorCode.MEMBER_UPDATE_CONFLICT);
        }
    }

    /**
     * 원인 사슬의 Hibernate 제약 위반이 그 제약인가. 예외 메시지가 아니라 드라이버가 알려 준 제약 이름으로 본다.
     * PostgreSQL 은 이름 그대로({@code uk_member_email}), H2(컨텍스트 테스트)는 이름 뒤에 인덱스 접미사를 붙여 포함 여부로 비교한다.
     */
    private static boolean violates(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return violation.getConstraintName().toLowerCase(Locale.ROOT).contains(constraintName);
            }
        }
        return false;
    }
}
