package com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobRequest;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.command.LegalDraftRegisterCommand;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.exception.LegalDraftErrorCode;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.exception.LegalDraftException;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.port.out.LegalDraftReceiptPort;
import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftInput;
import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftReceipt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 트랜잭션은 Facade 의 TransactionTemplate 이 연다 — 작업 등록과 영수증 INSERT 가 함께 커밋 · 롤백돼야 해서다. */
@Component
@RequiredArgsConstructor
public class LegalDraftRegistrationProcessor {
    private static final Pattern REQUEST_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{15,79}");
    private final LegalDraftReceiptPort receiptPort;

    public LegalDraftInput input(LegalDraftRegisterCommand command) {
        if (command.type() == null || command.templateVersion() == null) {
            throw new LegalDraftException(LegalDraftErrorCode.INVALID_REQUEST);
        }
        try {
            return new LegalDraftInput(command.type(), command.locale(), command.templateVersion(), command.confirmedFacts());
        } catch (IllegalArgumentException exception) {
            throw new LegalDraftException(LegalDraftErrorCode.INVALID_REQUEST);
        }
    }

    public String keyHash(long memberId, String key) {
        if (memberId <= 0 || key == null || !REQUEST_KEY.matcher(key).matches()) {
            throw new LegalDraftException(LegalDraftErrorCode.INVALID_REQUEST);
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest((memberId + ":" + key).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public Optional<LegalDraftReceipt> replay(String keyHash, LegalDraftInput input) {
        return receiptPort.findByKeyHash(keyHash).map(existing -> {
            if (!existing.input().equals(input)) {
                throw new LegalDraftException(LegalDraftErrorCode.KEY_CONFLICT);
            }
            return existing;
        });
    }

    public JobRequest jobRequest(long memberId) {
        return new JobRequest(LegalDraftJobProcessor.TYPE, "{}", null, memberId, 0, null);
    }

    public LegalDraftReceipt record(long memberId, String keyHash, LegalDraftInput input, Job job) {
        // 영수증 PK가 종료된 작업까지 멱등성을 보장한다. 경합에서 진 작업과 발행 신호는 같은 트랜잭션에서 함께 롤백된다.
        var receipt = new LegalDraftReceipt(keyHash, job.id(), memberId, input, null);
        receiptPort.insert(receipt);
        return receipt;
    }
}
