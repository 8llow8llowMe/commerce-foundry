package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.MemberRepository;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.mapper.MemberMapper;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberRepositoryAdapter implements MemberRepositoryPort {

    private final MemberRepository memberRepository;
    private final MemberMapper memberMapper;

    @Override
    public boolean existsByEmail(String email) {
        return memberRepository.existsByEmail(email);
    }

    @Override
    public Optional<Member> findById(long memberId) {
        return memberRepository.findById(memberId).map(memberMapper::toDomain);
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
