package com.commercefoundry.domainlayer.legaldocument.application.service.processor;

import com.commercefoundry.domainlayer.legaldocument.application.info.LegalDocumentInfos;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentKey;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** 직접 작성한 질문·빈칸 구조다. DB·프로필·외부 수집·AI 호출 없이 반환한다. */
@Component
public class LegalDocumentTemplateProcessor {
    public LegalDocumentInfos.Template get(LegalDocumentKey key) {
        boolean terms = key.type() == LegalDocumentKey.Type.TERMS;
        boolean korean = "ko".equals(key.locale());
        String title = korean ? (terms ? "이용약관 작성 초안" : "개인정보처리방침 작성 초안")
            : (terms ? "Terms drafting outline" : "Privacy notice drafting outline");
        String body = korean ? (terms ? TERMS_KO : PRIVACY_KO) : (terms ? TERMS_EN : PRIVACY_EN);
        List<String> checklist = korean ? List.of(
            "모든 {{...}} 빈칸을 실제 운영 사실로 바꾸고 불필요한 항목을 정리했는가?",
            "계약·처리 목적과 수집 항목·보유 기간 등 해당 문서의 사실을 담당자가 확인했는가?",
            "판매 국가·서비스 운영 방식에 맞는 최신 공식 자료와 전문가 검토를 확인했는가?",
            "고객에게 공개할 정보만 포함했고 비밀·회원 개인정보 원문을 제외했는가?"
        ) : List.of(
            "Replace every {{...}} placeholder with verified operational facts.",
            "Verify the contracts, purposes, data categories and retention periods relevant to this document.",
            "Check current primary guidance and obtain review for each market and operating model.",
            "Include only information intended for public display; exclude secrets and individual customer data."
        );
        String guidance = korean
            ? "검토용 시작 구조이며 완성 문서나 법적 적합성 보장이 아닙니다. 개인정보 항목 구성 참고: 개인정보보호위원회 개인정보 처리방침 작성지침(2026.4.) 안내 게시판. "
                + "저장·게시·번역은 자동 실행하지 않습니다. 영어 구조도 법률 번역을 보장하지 않습니다. 검토 확인 후 별도로 게시해 주세요."
            : "This is an original drafting structure, not a completed document or compliance guarantee. Consult current primary guidance and qualified review. "
                + "The English outline is not a guaranteed legal translation. Nothing is automatically saved, published or sent to AI.";
        return new LegalDocumentInfos.Template("legal-" + key.type().name().toLowerCase(Locale.ROOT) + "-" + key.locale(),
            1, key.type().name(), key.locale(), title, body, checklist, guidance);
    }

    private static final String TERMS_KO = """
        {{COMPANY_NAME}} 서비스 이용약관 작성 구조

        1. 서비스와 계약 주체
        {{SERVICE_SCOPE}}: 제공하는 서비스와 사업자 공개 정보를 확인해 작성하세요.
        2. 이용·주문·결제 흐름
        {{CONTRACT_AND_PAYMENT}}: 계약 성립 시점, 가격·결제·배송 안내를 실제 운영과 대조하세요.
        3. 취소·반품·교환과 문의
        {{CANCELLATION_AND_SUPPORT}}: 처리 절차, 고객 권리와 문의 창구를 검토하세요.
        4. 서비스 이용 범위와 분쟁 처리
        {{USER_RIGHTS_AND_DISPUTES}}: 적용 대상과 책임·분쟁 절차를 전문가와 검토하세요.
        5. 적용과 변경 안내
        {{PUBLICATION_AND_CHANGES}}: 적용 방식과 변경 공지 절차를 작성하세요.
        """;
    private static final String PRIVACY_KO = """
        {{COMPANY_NAME}} 개인정보처리방침 작성 구조

        1. 처리 주체·목적·항목
        {{DATA_PURPOSES_AND_FIELDS}}: 실제 수집 항목, 처리 근거와 목적을 확인하세요.
        2. 보유·파기
        {{RETENTION_AND_DELETION}}: 항목별 보유 기간과 파기 절차를 사실대로 작성하세요.
        3. 제공·위탁·국외 이전
        {{SHARING_PROCESSORS_AND_TRANSFERS}}: 실제 수탁자·제공·이전 여부와 조건을 확인하세요.
        4. 권리 행사·자동 수집·보호 조치
        {{RIGHTS_COOKIES_AND_SAFEGUARDS}}: 이용자 절차와 실제 운영 조치를 확인하세요.
        5. 책임자·문의·변경
        {{PRIVACY_CONTACT_AND_CHANGES}}: 공개 연락처와 변경 공지 절차를 검토하세요.
        """;
    private static final String TERMS_EN = """
        {{COMPANY_NAME}} terms drafting structure

        1. Service and contracting party
        {{SERVICE_SCOPE}}: Describe the actual service and public business information.
        2. Ordering, contracts, payment and delivery
        {{CONTRACT_AND_PAYMENT}}: Verify the real customer journey and contract terms.
        3. Cancellation, returns and support
        {{CANCELLATION_AND_SUPPORT}}: Review customer rights, procedures and contact channels.
        4. Use of the service and dispute handling
        {{USER_RIGHTS_AND_DISPUTES}}: Obtain market-specific review of rights and responsibilities.
        5. Publication and changes
        {{PUBLICATION_AND_CHANGES}}: Describe the intended notice and change process.
        """;
    private static final String PRIVACY_EN = """
        {{COMPANY_NAME}} privacy notice drafting structure

        1. Controller, purposes and data categories
        {{DATA_PURPOSES_AND_FIELDS}}: Verify actual collection, purposes and relevant bases.
        2. Retention and deletion
        {{RETENTION_AND_DELETION}}: Check periods and deletion procedures for each category.
        3. Sharing, processors and international transfers
        {{SHARING_PROCESSORS_AND_TRANSFERS}}: Describe verified recipients and arrangements.
        4. Rights, automatic collection and safeguards
        {{RIGHTS_COOKIES_AND_SAFEGUARDS}}: Verify the actual procedures and practices.
        5. Contact and changes
        {{PRIVACY_CONTACT_AND_CHANGES}}: Review public contact details and change notices.
        """;
}
