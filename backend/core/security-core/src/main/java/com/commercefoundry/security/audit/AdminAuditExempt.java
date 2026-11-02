package com.commercefoundry.security.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 어드민 쓰기 핸들러를 감사에서 뺀다는 선언 (#163, {@code security-guide.md} §11-4). 저장이 없거나(전시 미리보기) 본인 상태만 바꾸는(알림 읽음 · 온보딩 투어 ·
 * OTP 등록 시작) 핸들러만 쓴다. 업무 데이터를 바꾸는 핸들러는 {@link AdminAudited} 를 붙인다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAuditExempt {

    /** 면제 이유 — 비어 있으면 스모크 가드가 실패한다. */
    String reason();
}
