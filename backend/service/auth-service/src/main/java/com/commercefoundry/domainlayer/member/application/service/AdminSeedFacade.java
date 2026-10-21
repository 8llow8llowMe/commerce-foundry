package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminSeedOutcome;
import com.commercefoundry.domainlayer.member.application.port.in.AdminSeedUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminSeedProcessor;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 초기 관리자 시드 (#6, {@code security-guide.md} §11-3). 순서만 정한다 — 규칙 판정({@link AdminSeedProcessor#validate}) → 해시(트랜잭션 밖, BCrypt 는
 * 의도적으로 느린 CPU 작업) → 저장({@link AdminSeedProcessor#createIfAbsent} 만 트랜잭션). 유니크 키 충돌(동시 기동)도 그 트랜잭션이 롤백된 뒤 여기서 받아야
 * 건너뜀으로 끝난다.
 *
 * <p>설정이 틀리면 기동을 멈춘다({@link IllegalStateException}). 같은 이메일의 고객이 있는 것은 설정이 아니라 데이터 상태라 기동을 멈추지 않고 거절한다
 * ({@link AdminSeedOutcome#CUSTOMER_EXISTS}) — 그 이메일로 누군가 먼저 고객 가입을 해 두면 auth-service(고객 로그인 포함) 기동을 막을 수 있게 되기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class AdminSeedFacade implements AdminSeedUseCase {

    private final AdminSeedProcessor adminSeedProcessor;

    @Override
    public AdminSeedOutcome seed(AdminSeedCommand command) {
        Optional<AdminSeedCommand> validated = adminSeedProcessor.validate(command);
        if (validated.isEmpty()) {
            return AdminSeedOutcome.NOT_CONFIGURED;
        }
        AdminSeedCommand seed = validated.get();
        String passwordHash = adminSeedProcessor.hashPassword(seed.rawPassword());
        try {
            return adminSeedProcessor.createIfAbsent(seed.email(), passwordHash, seed.name());
        } catch (MemberException exception) {
            if (exception.getErrorCode() != MemberErrorCode.DUPLICATE_EMAIL) {
                throw exception;
            }
            return AdminSeedOutcome.CREATED_CONCURRENTLY;
        }
    }
}
