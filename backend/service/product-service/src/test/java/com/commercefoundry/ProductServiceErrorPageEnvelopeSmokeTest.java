package com.commercefoundry;

import com.commercefoundry.common.support.ErrorPageEnvelopeSmokeTestSupport;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Instant;

/**
 * MVC 밖 오류 봉투 스모크 — 실제 서블릿 컨테이너(RANDOM_PORT)에서 방화벽 거부(400)가 이 서비스에서도 {@code /error} 를 거쳐 {@code COMMON_004} 봉투로 나가는지 본다
 * ({@code api-design-guide.md} §2-1 공통 오류 코드). RANDOM_PORT 라 {@link ProductServiceApplicationTests} 와 컨텍스트를 같이 쓰지 못한다 — 서비스에 하나만 둔다.
 */
class ProductServiceErrorPageEnvelopeSmokeTest extends ErrorPageEnvelopeSmokeTestSupport {

    @Override
    protected String customerAuthorization() {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
    }
}
