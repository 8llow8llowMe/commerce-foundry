package com.commercefoundry.domainlayer.legaldocument.application.service.processor;

import com.commercefoundry.domainlayer.legaldocument.application.info.LegalDocumentInfos;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentKey;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentTemplate;
import org.springframework.stereotype.Component;

/** 직접 작성한 질문·빈칸 구조({@link LegalDocumentTemplate})를 어드민 조회용 Info 로 감싼다. DB·프로필·외부 수집·AI 호출이 없다. */
@Component
public class LegalDocumentTemplateProcessor {
    public LegalDocumentInfos.Template get(LegalDocumentKey key) {
        LegalDocumentTemplate template = LegalDocumentTemplate.of(key);
        return new LegalDocumentInfos.Template(template.templateId(), template.version(), template.type().name(), template.locale(),
            template.title(), template.body(), template.checklist(), template.guidance());
    }
}
