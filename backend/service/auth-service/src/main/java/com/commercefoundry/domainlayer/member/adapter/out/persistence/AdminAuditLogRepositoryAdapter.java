package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import com.commercefoundry.domainlayer.member.adapter.out.log.AdminAuditLogAdapter;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminAuditLogEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.AdminAuditLogRepository;
import com.commercefoundry.domainlayer.member.application.mapper.AdminAuditLogMapper;
import com.commercefoundry.domainlayer.member.application.model.AdminAuditRecord;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditLogRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditPort;
import com.commercefoundry.domainlayer.member.domain.model.AdminAuditLog;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.principal.MemberRole;
import java.util.OptionalLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 감사 기록 (#192, {@code security-guide.md} §11-4) — {@code admin.audit} 로거 한 줄을 먼저 남기고 {@code auth.admin_audit_log} 에 한 행을 더한다.
 *
 * <p><b>감사 저장 실패가 업무 응답을 바꾸지 않는다</b>({@link AdminAuditPort} 계약). DB 쓰기는 {@code REQUIRES_NEW} 짧은 트랜잭션이고, 예외는 그 트랜잭션
 * <b>바깥</b>에서 잡는다 — 커밋 시점 예외까지 삼키고, 뒷날 업무 트랜잭션 안에서 불려도 업무를 rollback-only 로 만들지 않으며 업무가 롤백돼도 감사는 남는다.
 * 지금 호출자(로그인 · 회원 목록)는 모두 트랜잭션 밖이라 커넥션을 하나만 쓴다 — <b>업무 트랜잭션 안에서 부르면 스레드가 커넥션 2개를 잡는다</b>(동시 요청이
 * 풀 크기에 닿으면 교착). 실패하면 error 한 줄(행위 · 관리자만)을 남기고, 그 기간은 로그 줄이 정본이다.
 *
 * <p>행위자도 대상도 없는 기록(없는 이메일 · 고객 계정으로 관리자 로그인 실패)은 로그 줄만 남기고 행을 만들지 않는다
 * ({@link AdminAuditRecord#persistable()}) — 인증 전 요청이라 이메일을 바꿔 가며 반복하면 행이 끝없이 쌓인다. 노린 관리자 계정이 있는 실패는 남긴다.
 *
 * <p>아이디(Snowflake)는 여기서 만든다 — 포트가 행위별 메서드라 Processor 가 만든 domain 입력이 없다.
 */
@Slf4j
@Component
public class AdminAuditLogRepositoryAdapter implements AdminAuditPort, AdminAuditLogRepositoryPort {

    private final AdminAuditLogAdapter adminAuditLogAdapter;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final AdminAuditLogMapper adminAuditLogMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final TransactionTemplate requiresNew;

    public AdminAuditLogRepositoryAdapter(
        AdminAuditLogAdapter adminAuditLogAdapter,
        AdminAuditLogRepository adminAuditLogRepository,
        AdminAuditLogMapper adminAuditLogMapper,
        SnowflakeIdGenerator snowflakeIdGenerator,
        PlatformTransactionManager transactionManager
    ) {
        this.adminAuditLogAdapter = adminAuditLogAdapter;
        this.adminAuditLogRepository = adminAuditLogRepository;
        this.adminAuditLogMapper = adminAuditLogMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void adminLoginSucceeded(long adminMemberId) {
        record(AdminAuditRecord.adminLoginSucceeded(adminMemberId));
    }

    @Override
    public void adminLoginFailed(OptionalLong targetAdminMemberId) {
        record(AdminAuditRecord.adminLoginFailed(targetAdminMemberId));
    }

    @Override
    public void membersSearched(long adminMemberId, MemberRole role, int page, int size) {
        record(AdminAuditRecord.membersSearched(adminMemberId, role, page, size));
    }

    /** 조건 조합마다 파생 쿼리 하나 — 모두 아이디 내림차순이다. */
    @Override
    public Page<AdminAuditLog> findPage(String action, Long actorMemberId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<AdminAuditLogEntity> logs;
        if (action != null && actorMemberId != null) {
            logs = adminAuditLogRepository.findAllByActionAndActorMemberIdOrderByIdDesc(action, actorMemberId, pageable);
        } else if (action != null) {
            logs = adminAuditLogRepository.findAllByActionOrderByIdDesc(action, pageable);
        } else if (actorMemberId != null) {
            logs = adminAuditLogRepository.findAllByActorMemberIdOrderByIdDesc(actorMemberId, pageable);
        } else {
            logs = adminAuditLogRepository.findAllByOrderByIdDesc(pageable);
        }
        return logs.map(adminAuditLogMapper::toDomain);
    }

    private void record(AdminAuditRecord record) {
        adminAuditLogAdapter.write(record);
        if (!record.persistable()) {
            return;
        }
        try {
            requiresNew.executeWithoutResult(status -> adminAuditLogRepository.saveAndFlush(AdminAuditLogEntity.builder()
                .id(snowflakeIdGenerator.generateId())
                .action(record.action().name())
                .actorMemberId(record.actorMemberId())
                .target(record.target())
                .result(record.result())
                .build()));
        } catch (RuntimeException exception) {
            log.error("admin audit persist failed. action={}, actorMemberId={}, error={}",
                record.action(), record.actorMemberId(), exception.getClass().getSimpleName(), exception);
        }
    }
}
