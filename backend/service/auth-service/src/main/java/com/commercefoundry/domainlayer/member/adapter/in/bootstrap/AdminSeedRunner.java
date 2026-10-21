package com.commercefoundry.domainlayer.member.adapter.in.bootstrap;

import com.commercefoundry.domainlayer.member.adapter.in.support.MemberMasking;
import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.info.AdminSeedOutcome;
import com.commercefoundry.domainlayer.member.application.port.in.AdminSeedUseCase;
import com.commercefoundry.global.properties.AdminSeedProperties;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 초기 관리자 시드 (#6) — 기동이 부르는 진입점이다. HTTP({@code in/web}) · 시각({@code in/scheduler})과 나란히 "무엇이 유스케이스를 부르는가" 로 나눈
 * inbound adapter 라 {@code adapter/in/bootstrap} 에 둔다 ({@code architecture-guide.md} §2). {@code global} 은 설정 · 빈 조립만 두는 곳이라 맞지 않는다.
 *
 * <p>설정을 커맨드로 옮겨 UseCase 만 부른다. 설정이 틀리면 UseCase 의 {@link IllegalStateException} 이 그대로 나가 기동이 멈춘다.
 * 결과는 한 줄로 남긴다 — 이메일은 마스킹({@link MemberMasking})하고 비밀번호 · 이름은 남기지 않는다. 같은 이메일의 고객이 있어 거절했으면 warn 이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeedRunner implements ApplicationRunner {

    private final AdminSeedProperties adminSeedProperties;
    private final AdminSeedUseCase adminSeedUseCase;

    @Override
    public void run(ApplicationArguments args) {
        AdminSeedOutcome outcome = adminSeedUseCase.seed(new AdminSeedCommand(adminSeedProperties.email(), adminSeedProperties.password(), adminSeedProperties.name()));
        if (outcome == AdminSeedOutcome.NOT_CONFIGURED) {
            log.info("admin seed result={}", outcome);
            return;
        }
        String maskedEmail = MemberMasking.maskEmail(adminSeedProperties.email().strip().toLowerCase(Locale.ROOT));
        if (outcome == AdminSeedOutcome.CUSTOMER_EXISTS) {
            log.warn("admin seed result={} email={} — the email belongs to a customer and is not promoted; use another email", outcome, maskedEmail);
            return;
        }
        log.info("admin seed result={} email={}", outcome, maskedEmail);
    }
}
