package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.shippingaddress.adapter.out.persistence.entity.ShippingAddressEntity;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배송지 주소록 API 전체 흐름을 실제 빈 구성(보안 체인 · 두 advice · Facade · 회원 잠금 · 어댑터 · H2)으로 본다.
 * 애노테이션은 {@link AuthServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다. H2 는 컨텍스트 동안 살아 있으므로 테스트마다 회원을 새로 만든다.
 *
 * <p>동시 요청(잠금 · 문장 단위 유니크 검사)은 PostgreSQL 에서만 의미가 있어 {@code ShippingAddressConcurrencyTest}(Testcontainers)가 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ShippingAddressFlowIntegrationTest {

    private static final String BASE = "/api/v1/members/me/shipping-addresses";
    private static final AtomicLong MEMBER_IDS = new AtomicLong(System.currentTimeMillis());

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private MemberRepositoryPort memberRepositoryPort;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("생성 → 첫 주소 자동 기본 → 둘째를 기본으로 → 목록 순서 → 수정 → 기본 삭제 시 최근 주소 승격 → 마지막 삭제")
    void fullFlow() throws Exception {
        String token = bearer(newMember());

        String first = create(token, domestic("집", "010-1234-5678", "06236"));
        String second = create(token, domestic("회사", "02-123-4567", "04524"));
        String third = create(token, domestic(null, "01098765432", "48058").replace("}", ",\"isDefault\":false}"));

        assertThat(listIds(token)).as("첫 주소가 기본, 나머지는 최신순").containsExactly(first, third, second);
        assertThat(listDefaults(token)).containsExactly(true, false, false);
        MvcTestResult stored = get(token, first);
        assertThat(stored).hasStatusOk();
        assertThat(stored).bodyJson().extractingPath("$.dataBody.recipientPhone").isEqualTo("01012345678");
        assertThat(stored).bodyJson().extractingPath("$.dataBody.label").isEqualTo("집");
        assertThat(stored).bodyJson().extractingPath("$.dataBody.isDefault").isEqualTo(true);

        assertThat(setDefault(token, second)).hasStatusOk();
        assertThat(setDefault(token, second)).as("이미 기본이어도 200 — 멱등").hasStatusOk();
        assertThat(listIds(token)).containsExactly(second, third, first);
        assertThat(listDefaults(token)).containsExactly(true, false, false);

        MvcTestResult updated = put(token, third, international().replace("}", ",\"isDefault\":true}"));
        assertThat(updated).hasStatusOk();
        assertThat(updated).bodyJson().extractingPath("$.dataBody.countryCode").isEqualTo("US");
        assertThat(updated).bodyJson().extractingPath("$.dataBody.recipientPhone").isEqualTo("+14155550100");
        assertThat(updated).bodyJson().extractingPath("$.dataBody.postalCode").isNull();
        assertThat(updated).bodyJson().extractingPath("$.dataBody.isDefault").as("전체 교체는 기본 여부를 바꾸지 않는다").isEqualTo(false);
        assertThat(listDefaults(token)).containsExactly(true, false, false);

        MvcTestResult deleted = delete(token, second);
        assertThat(deleted).hasStatusOk();
        assertThat(deleted).bodyJson().extractingPath("$.dataBody").isNull();
        assertThat(listIds(token)).as("기본을 지우면 가장 최근 주소가 기본").containsExactly(third, first);
        assertThat(listDefaults(token)).containsExactly(true, false);

        assertThat(delete(token, first)).hasStatusOk();
        assertThat(delete(token, third)).hasStatusOk();
        assertThat(listIds(token)).isEmpty();
        assertThat(delete(token, third)).as("이미 지운 주소는 404").hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("isDefault=true 로 추가하면 기존 기본이 풀리고 새 주소가 기본이다")
    void createAsDefault() throws Exception {
        String token = bearer(newMember());
        String first = create(token, domestic("집", "01012345678", "06236"));

        String second = create(token, domestic("회사", "01012345678", "06236").replace("}", ",\"isDefault\":true}"));

        assertThat(listIds(token)).containsExactly(second, first);
        assertThat(listDefaults(token)).containsExactly(true, false);
    }

    @Test
    @DisplayName("다른 회원의 토큰으로는 그 주소를 GET · PUT · DELETE · 기본 지정할 수 없다 — 모두 404 · AUTH_020 이고 원래 주소는 그대로다")
    void otherMembersAddressIsHidden() throws Exception {
        String owner = bearer(newMember());
        String other = bearer(newMember());
        String addressId = create(owner, domestic("집", "01012345678", "06236"));
        create(other, domestic("집", "01012345678", "06236"));

        assertNotFound(get(other, addressId));
        assertNotFound(put(other, addressId, international()));
        assertNotFound(delete(other, addressId));
        assertNotFound(setDefault(other, addressId));
        assertNotFound(get(other, "4611686018427387904"));

        assertThat(listIds(other)).doesNotContain(addressId);
        MvcTestResult stillThere = get(owner, addressId);
        assertThat(stillThere).hasStatusOk();
        assertThat(stillThere).bodyJson().extractingPath("$.dataBody.countryCode").isEqualTo("KR");
        assertThat(stillThere).bodyJson().extractingPath("$.dataBody.isDefault").isEqualTo(true);
    }

    @Test
    @DisplayName("회원당 상한(20)을 넘으면 409 · AUTH_021 이고 저장하지 않는다")
    void limitExceeded() throws Exception {
        long memberId = newMember();
        String token = bearer(memberId);
        for (int i = 0; i < 20; i++) {
            create(token, domestic(null, "01012345678", "06236"));
        }

        MvcTestResult result = post(token, domestic(null, "01012345678", "06236"));

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_021");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("배송지는 최대 20개까지 저장할 수 있습니다.");
        assertThat(listIds(token)).hasSize(20);
        assertThat(listDefaults(token)).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(rows(memberId)).hasSize(20);
    }

    @Test
    @DisplayName("토큰은 유효하지만 회원이 없으면 추가 · 기본 지정 · 삭제는 404 · AUTH_003 이다 (회원 잠금에서 걸린다)")
    void unknownMember() {
        String token = bearer(MEMBER_IDS.incrementAndGet());

        MvcTestResult result = post(token, domestic(null, "01012345678", "06236"));

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_003");
        assertThat(mvc.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, token).exchange()).as("목록은 빈 배열").hasStatusOk();
    }

    @Test
    @DisplayName("기본 배송지 유니크 키는 H2 에서도 걸린다 — 컨텍스트 테스트의 스키마도 같은 제약이다")
    void uniqueDefaultInH2() {
        long memberId = newMember();
        transactionTemplate.executeWithoutResult(status -> entityManager.persist(row(System.nanoTime(), memberId)));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            entityManager.persist(row(System.nanoTime(), memberId));
            entityManager.flush();
        })).rootCause().message().containsIgnoringCase(ShippingAddressEntity.UK_DEFAULT_MEMBER_ID);
    }

    private long newMember() {
        long memberId = MEMBER_IDS.incrementAndGet();
        memberRepositoryPort.save(Member.signUp(memberId, "member-" + UUID.randomUUID() + "@example.com", "$2a$10$hash", "홍길동", null));
        return memberId;
    }

    private String create(String token, String json) throws Exception {
        MvcTestResult result = post(token, json);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.dataBody.addressId");
    }

    private MvcTestResult post(String token, String json) {
        return mvc.post().uri(BASE).header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private MvcTestResult get(String token, String addressId) {
        return mvc.get().uri(BASE + "/" + addressId).header(HttpHeaders.AUTHORIZATION, token).exchange();
    }

    private MvcTestResult put(String token, String addressId, String json) {
        return mvc.put().uri(BASE + "/" + addressId).header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private MvcTestResult delete(String token, String addressId) {
        return mvc.delete().uri(BASE + "/" + addressId).header(HttpHeaders.AUTHORIZATION, token).exchange();
    }

    private MvcTestResult setDefault(String token, String addressId) {
        return mvc.put().uri(BASE + "/" + addressId + "/default").header(HttpHeaders.AUTHORIZATION, token).exchange();
    }

    private List<String> listIds(String token) throws Exception {
        return JsonPath.read(list(token), "$.dataBody.addresses[*].addressId");
    }

    private List<Boolean> listDefaults(String token) throws Exception {
        return JsonPath.read(list(token), "$.dataBody.addresses[*].isDefault");
    }

    private String list(String token) throws Exception {
        MvcTestResult result = mvc.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, token).exchange();
        assertThat(result).hasStatusOk();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private List<ShippingAddressEntity> rows(long memberId) {
        return transactionTemplate.execute(status -> entityManager
            .createQuery("select a from ShippingAddressEntity a where a.memberId = :memberId", ShippingAddressEntity.class)
            .setParameter("memberId", memberId)
            .getResultList());
    }

    private static ShippingAddressEntity row(long id, long memberId) {
        return ShippingAddressEntity.builder()
            .id(id).memberId(memberId).recipientName("홍길동").recipientPhone("01012345678").countryCode("KR").postalCode("06236")
            .addressLine1("서울특별시 강남구 테헤란로 123").defaultMemberId(memberId)
            .build();
    }

    private static void assertNotFound(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_020");
    }

    private static String domestic(String label, String phone, String postalCode) {
        String labelJson = label == null ? "null" : "\"" + label + "\"";
        return """
            {"label":%s,"recipientName":" 홍길동 ","recipientPhone":"%s","countryCode":"KR","postalCode":"%s",\
            "addressLine1":"서울특별시 강남구 테헤란로 123","addressLine2":"4층"}""".formatted(labelJson, phone, postalCode);
    }

    private static String international() {
        return """
            {"recipientName":"John Doe","recipientPhone":"+1 415-555-0100","countryCode":"US",\
            "addressLine1":"1 Market St","city":"San Francisco","stateProvince":"CA"}""";
    }

    private static String bearer(long memberId) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(memberId, MemberRole.CUSTOMER).tokenValue();
    }
}
