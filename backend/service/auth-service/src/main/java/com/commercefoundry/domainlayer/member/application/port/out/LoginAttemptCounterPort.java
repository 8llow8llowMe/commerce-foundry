package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptOutcome;
import java.time.Duration;

/**
 * 로그인 · 회원가입 시도 수를 이메일 단위 고정 창으로 센다 (#66). 이메일은 부르는 쪽이 정규화해 넘긴다 — 대소문자 · 앞뒤 공백만 바꿔 새 창을 열지 못하게.
 * 해시 방식과 키 형식은 구현이 정한다 — 부르는 쪽은 종류와 이메일만 넘기고, 구현은 이메일 원문을 키 · 로그에 남기지 않는다.
 *
 * <p>지금 주체는 이메일 하나다. 게이트웨이가 클라이언트 IP 를 넘기게 되면(#135) IP 주체를 더한다.
 *
 * <p>모든 메서드는 저장소에 닿지 못하면 {@link MemberException}({@code TOKEN_STORE_UNAVAILABLE}, 503)을 던진다.
 * 그때 요청을 막을지 통과시킬지는 부르는 쪽이 정한다. 허용 횟수 판정도 부르는 쪽이 한다 — 저장소는 세기만 한다.
 */
public interface LoginAttemptCounterPort {

    /**
     * 시도 1회를 더하고, 창 안의 시도 수와 창의 남은 시간을 함께(원자) 돌려준다. 창은 첫 시도에만 건다 — 이어지는 시도는 창을 늘리지 않는다.
     *
     * @param normalizedEmail 정규화한 이메일
     * @param window          첫 시도일 때 걸 창의 길이. 0 보다 커야 한다
     */
    LoginAttemptOutcome record(AttemptKind kind, String normalizedEmail, Duration window);

    /** 그 이메일의 창을 지운다 — 다음 시도는 1부터 센다. 이미 없으면 아무 일도 없다. */
    void reset(AttemptKind kind, String normalizedEmail);
}
