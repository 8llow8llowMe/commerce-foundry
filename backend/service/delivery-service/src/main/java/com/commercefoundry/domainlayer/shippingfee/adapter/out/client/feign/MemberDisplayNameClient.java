package com.commercefoundry.domainlayer.shippingfee.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.client.feign.dto.MemberDisplayNameInternalResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * auth-service internal 회원 표시 이름 벌크 조회 (#322). 서비스 토큰을 싣는다({@link InternalFeignClientConfiguration}) — 사용자 {@code Authorization} 은 넘기지 않는다
 * ({@code security-guide.md} §9-5). {@code name} 은 대상의 Eureka 서비스 ID 이자 서비스 토큰의 {@code aud} 다.
 * auth 는 이 엔드포인트의 허용 호출자로 {@code SERVICE_delivery-service} 를 선언한다.
 *
 * <p>직접 부르지 않는다 — 서킷 · 예외 변환은 {@code MemberDisplayNameInternalResponseSupport} 가 감싸고, application 에는
 * {@code MemberDisplayNameQueryPort} 로만 나간다. 응답의 이름은 이미 마스킹되어 있다. 원문은 받지 않는다.
 */
@FeignClient(
    name = "${feign-client.target-services.auth-service:auth-service}",
    contextId = "memberDisplayNameClient",
    configuration = InternalFeignClientConfiguration.class)
public interface MemberDisplayNameClient {

    /** 한 번에 보낼 수 있는 아이디 수 — auth-service 벌크 조회 상한 ({@code api-design-guide.md} §1-3). */
    int MAX_IDS_PER_CALL = 100;

    /**
     * @param ids 1~{@value #MAX_IDS_PER_CALL}개. {@code ids=1&ids=2} 로 보낸다
     */
    @GetMapping("/internal/v1/member-display-names")
    Response<MemberDisplayNameInternalResponse> getDisplayNames(@RequestParam("ids") List<Long> ids);
}
