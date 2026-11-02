package com.commercefoundry.security.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 어드민 쓰기 핸들러가 감사 줄을 남긴다는 선언 (#163, {@code security-guide.md} §11-4). {@code /api/v1/admin/**} 의 POST · PUT · PATCH · DELETE 핸들러는
 * 이것 또는 {@link AdminAuditExempt} 를 붙여야 한다 — 인가 스모크({@code ResourceServerSmokeTestSupport#adminWriteHandlersDeclareAudit})가 실제 매핑으로 본다.
 *
 * <p>애노테이션 자체는 줄을 쓰지 않는다. 줄은 Facade 가 서비스의 {@code adminaudit} Processor 를 거쳐 {@link AdminAuditLog#write} 로 쓴다.
 * {@link #action()} 은 서비스의 행위 enum 값과 같아야 한다(서비스 일치 테스트).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAudited {

    /** 행위 이름 {@code <RESOURCE>_<VERB>} — 예: {@code CATEGORY_CREATE}. */
    String action();
}
