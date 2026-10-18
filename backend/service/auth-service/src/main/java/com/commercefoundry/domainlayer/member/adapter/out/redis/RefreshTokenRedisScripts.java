package com.commercefoundry.domainlayer.member.adapter.out.redis;

import java.util.List;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * refresh 토큰 Lua 스크립트. 컬렉션(HASH)은 TTL 과 같은 스크립트에서 쓴다 — 사이에 실패해 영구 키가 남지 않게 ({@code coding-conventions.md} §9-7).
 * 시각 · TTL · 유예는 밀리초 정수 문자열로 받는다(ARGV). 서버 시계({@code TIME})를 쓰지 않는다 — 판정 기준을 애플리케이션 {@code Clock} 하나로 둔다
 * (인스턴스 간 시계는 NTP 로 맞춘다는 전제다).
 *
 * <p>standalone 전용이다. Redis Cluster 로 바꾸면 한 스크립트의 키(토큰 · family · 새 토큰)가 같은 슬롯에 있어야 해(CROSSSLOT)
 * 키에 같은 hash tag(예: {@code {familyId}})를 넣어야 한다.
 */
final class RefreshTokenRedisScripts {

    /** 해시 필드 이름. */
    static final String MEMBER_ID = "memberId";
    static final String FAMILY_ID = "familyId";
    static final String ROTATED_AT = "rotatedAt";
    static final String SIBLINGS = "siblings";

    /**
     * 로그인 — 새 토큰 HASH 와 새 family 를 함께 저장한다. family 값은 최초 로그인 시각이다 — 절대 수명을 재는 기준이라 회전이 다시 쓰지 않는다.
     *
     * <pre>
     * KEYS[1] 토큰  KEYS[2] family
     * ARGV[1] memberId  ARGV[2] familyId  ARGV[3] TTL(ms)  ARGV[4] 로그인 시각(epoch ms)
     * 반환 1
     * </pre>
     */
    static final RedisScript<Long> SAVE_NEW_FAMILY = RedisScript.of("""
        redis.call('HSET', KEYS[1], 'memberId', ARGV[1], 'familyId', ARGV[2])
        redis.call('PEXPIRE', KEYS[1], ARGV[3])
        redis.call('SET', KEYS[2], ARGV[4], 'PX', ARGV[3])
        return 1
        """, Long.class);

    /**
     * 회전 — 읽은 세션을 다시 확인하고 한 번에 판정한다. 반환은 {@code {RefreshTokenRotation 이름, 건 TTL(ms)}} 이고 새 토큰을 저장하지 않았으면 TTL 은 0 이다.
     *
     * <pre>
     * KEYS[1] 옛 토큰  KEYS[2] family  KEYS[3] 새 토큰
     * ARGV[1] memberId  ARGV[2] familyId  ARGV[3] 지금(epoch ms)  ARGV[4] 재사용 유예(ms)  ARGV[5] TTL(ms)  ARGV[6] 형제 상한
     * ARGV[7] family 절대 수명(ms)
     * </pre>
     *
     * <ul>
     *   <li>옛 토큰이 없거나 · memberId · familyId 가 읽은 값과 다르거나 · family 가 없다 → {@code INVALID} (아무것도 바꾸지 않는다)</li>
     *   <li>family 값(로그인 시각)이 정수 문자열({@code ^%d+$})이 아니다 → family 삭제 → {@code INVALID}. 배포 전이라 옛 값 {@code "1"} 은 없지만
     *       남아 있다면 정수로 읽혀 1970년 로그인이 되고 바로 아래 분기에서 만료된다</li>
     *   <li>절대 수명 − (지금 − 로그인 시각, 음수는 0 — 인스턴스 간 시계 차이) &lt; 1초 → family 삭제 → {@code FAMILY_EXPIRED}. 재사용 판정보다 먼저다.
     *       1초 미만은 응답 {@code refreshTokenExpiresIn}(초, 버림)이 0 이 되어 BFF 쿠키가 바로 지워지므로 회전하지 않는다
     *       ({@link com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy#MIN_REMAINING_LIFETIME})</li>
     *   <li>rotatedAt 이 없다 → rotatedAt=지금, 새 토큰 저장, family TTL 다시 걸기 → {@code ROTATED}. 옛 토큰의 TTL 은 그대로다</li>
     *   <li>rotatedAt 이 있고 지금 − rotatedAt ≤ 유예(음수는 0) → siblings 를 하나 올리고,
     *       상한 안이면 새 토큰(형제) 저장 · family TTL 다시 걸기, <b>rotatedAt 은 그대로</b> → {@code REISSUED_IN_GRACE}</li>
     *   <li>유예 밖 · 형제 상한 초과 · rotatedAt 이 정수 문자열이 아니다 → family 삭제 → {@code REUSE_DETECTED}</li>
     * </ul>
     * 새 토큰 · family 의 TTL 은 {@code min(TTL, 절대 수명 − 경과)} 다. 남은 시간이 1초 미만인 경우는 만료 분기가 먼저 막는다.
     * 저장값은 {@code tonumber} 만으로 받지 않는다 — strtod 라 {@code "nan"} · {@code "inf"} · {@code "0x10"} · {@code "1e3"} 도 숫자가 된다.
     * family 값은 PEXPIRE 만 하고 다시 쓰지 않는다 — 로그인 시각이 회전으로 밀리지 않게.
     * PEXPIRE 에는 TTL 을 정수 문자열로 넘긴다 — Lua 숫자(double)를 그대로 넘기면 Redis 판에 따라 지수 표기로 바뀔 수 있다.
     * HMGET 은 없는 필드를, GET 은 없는 키를 false 로 준다 — 문자열 ARGV 와 같을 수 없어 첫 줄이 없는 토큰 · family 까지 걸러 낸다.
     * 키 타입이 HASH · STRING 이 아니면(WRONGTYPE) 스크립트 오류다 — 어댑터가 저장소 장애(503)로 바꾼다.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final RedisScript<List<Object>> ROTATE = (RedisScript) RedisScript.of("""
        local stored = redis.call('HMGET', KEYS[1], 'memberId', 'familyId', 'rotatedAt')
        local family = redis.call('GET', KEYS[2])
        if stored[1] ~= ARGV[1] or stored[2] ~= ARGV[2] or not family then
          return {'INVALID', 0}
        end
        if not string.match(family, '^%d+$') then
          redis.call('DEL', KEYS[2])
          return {'INVALID', 0}
        end
        local loginAt = tonumber(family)
        local now = tonumber(ARGV[3])
        local maxLifetime = tonumber(ARGV[7])
        local age = now - loginAt
        if age < 0 then
          age = 0
        end
        local remaining = maxLifetime - age
        if remaining < 1000 then
          redis.call('DEL', KEYS[2])
          return {'FAMILY_EXPIRED', 0}
        end
        local ttl = math.min(tonumber(ARGV[5]), remaining)
        local ttlArg = string.format('%.0f', ttl)
        local result = 'ROTATED'
        if stored[3] then
          if not string.match(stored[3], '^%d+$') then
            redis.call('DEL', KEYS[2])
            return {'REUSE_DETECTED', 0}
          end
          local rotatedAt = tonumber(stored[3])
          local elapsed = now - rotatedAt
          if elapsed < 0 then
            elapsed = 0
          end
          if elapsed > tonumber(ARGV[4]) then
            redis.call('DEL', KEYS[2])
            return {'REUSE_DETECTED', 0}
          end
          if redis.call('HINCRBY', KEYS[1], 'siblings', 1) > tonumber(ARGV[6]) then
            redis.call('DEL', KEYS[2])
            return {'REUSE_DETECTED', 0}
          end
          result = 'REISSUED_IN_GRACE'
        else
          redis.call('HSET', KEYS[1], 'rotatedAt', ARGV[3])
        end
        redis.call('HSET', KEYS[3], 'memberId', ARGV[1], 'familyId', ARGV[2])
        redis.call('PEXPIRE', KEYS[3], ttlArg)
        redis.call('PEXPIRE', KEYS[2], ttlArg)
        return {result, ttl}
        """, List.class);

    private RefreshTokenRedisScripts() {
    }
}
