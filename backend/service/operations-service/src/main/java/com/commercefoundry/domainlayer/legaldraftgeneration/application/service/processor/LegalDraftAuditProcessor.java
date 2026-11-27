package com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor;

import com.commercefoundry.security.audit.AdminAuditLog;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LegalDraftAuditProcessor {
    public static final String REGISTER = "LEGAL_DRAFT_GENERATION_REGISTER";
    public static final String REVIEW = "LEGAL_DRAFT_GENERATION_REVIEW";
    public static final String CANCEL = "LEGAL_DRAFT_GENERATION_CANCEL";

    public <T> T record(String action, long memberId, Long jobId, Supplier<T> operation, ToLongFunction<T> resultId) {
        T result;
        try {
            result = operation.get();
        } catch (RuntimeException exception) {
            write(action, memberId, jobId, false);
            throw exception;
        }
        write(action, memberId, resultId.applyAsLong(result), true);
        return result;
    }

    private void write(String action, long memberId, Long jobId, boolean success) {
        try {
            String target = jobId == null ? null : AdminAuditLog.target("legal-draft-generation", jobId);
            AdminAuditLog.write(action, memberId, target, success);
        } catch (RuntimeException exception) {
            log.warn("법률 문서 생성 감사 기록 실패. action={}", action);
        }
    }
}
