package com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.application.service.processor.JobRegistrationProcessor;
import com.commercefoundry.domainlayer.job.domain.model.JobRequest;
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
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class LegalDraftRegistrationProcessor {
    private static final Pattern REQUEST_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{15,79}");
    private final LegalDraftReceiptPort receiptPort;
    private final JobRegistrationProcessor jobs;

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

    @Transactional(readOnly = true)
    public Optional<LegalDraftReceipt> replay(String keyHash, LegalDraftInput input) {
        return receiptPort.findByKeyHash(keyHash).map(existing -> {
            if (!existing.input().equals(input)) {
                throw new LegalDraftException(LegalDraftErrorCode.KEY_CONFLICT);
            }
            return existing;
        });
    }

    @Transactional
    public LegalDraftReceipt insert(long memberId, String keyHash, LegalDraftInput input) {
        // 영수증 PK가 종료된 작업까지 멱등성을 보장한다. 경합에서 진 작업과 발행 신호는 함께 롤백한다.
        var job = jobs.register(new JobRequest(LegalDraftJobProcessor.TYPE, "{}", null, memberId, 0, null)).job();
        var receipt = new LegalDraftReceipt(keyHash, job.id(), memberId, input, null);
        receiptPort.insert(receipt);
        return receipt;
    }
}
