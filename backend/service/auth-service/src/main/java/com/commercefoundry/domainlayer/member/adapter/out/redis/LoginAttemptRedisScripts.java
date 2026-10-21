package com.commercefoundry.domainlayer.member.adapter.out.redis;

import java.util.List;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 로그인 · 회원가입 시도 계수 Lua 스크립트. 더하기와 TTL 을 한 스크립트에서 해 사이에 실패해도 영구 키가 남지 않는다 ({@code coding-conventions.md} §9-7).
 * 창은 밀리초 정수 문자열로 받는다(ARGV).
 */
final class LoginAttemptRedisScripts {

    /**
     * 시도 1회 — 고정 창 카운터. 반환은 {@code {창 안의 시도 수, 창의 남은 시간(ms)}} 이고 둘 다 정수 응답(Long)이다.
     *
     * <pre>
     * KEYS[1] 시도 키
     * ARGV[1] 창(ms)
     * </pre>
     *
     * <ul>
     *   <li>INCR 결과가 1(첫 시도)일 때만 PEXPIRE 를 건다 — 이어지는 시도는 창을 늘리지 않는다</li>
     *   <li>남은 시간은 같은 스크립트의 PTTL 이다. TTL 이 없으면(음수 — 다른 경로로 TTL 없이 생긴 키) 창을 다시 걸고 창 전체를 돌려준다.
     *       영구 키가 남아 그 이메일이 영영 묶이지 않게</li>
     * </ul>
     * 키 타입이 STRING 이 아니거나(WRONGTYPE) 값이 정수가 아니면 스크립트 오류다 — 어댑터가 저장소 장애(503)로 바꾼다.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final RedisScript<List<Object>> RECORD = (RedisScript) RedisScript.of("""
        local count = redis.call('INCR', KEYS[1])
        if count == 1 then
          redis.call('PEXPIRE', KEYS[1], ARGV[1])
        end
        local ttl = redis.call('PTTL', KEYS[1])
        if ttl < 0 then
          redis.call('PEXPIRE', KEYS[1], ARGV[1])
          ttl = tonumber(ARGV[1])
        end
        return {count, ttl}
        """, List.class);

    private LoginAttemptRedisScripts() {
    }
}
