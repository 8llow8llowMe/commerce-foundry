package com.commercefoundry.domainlayer.member.adapter.out.redis;

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

    /** family 키의 값 — 뜻은 없고 존재만 본다. */
    static final String FAMILY_ALIVE = "1";

    /**
     * 로그인 — 새 토큰 HASH 와 새 family 를 함께 저장한다.
     *
     * <pre>
     * KEYS[1] 토큰  KEYS[2] family
     * ARGV[1] memberId  ARGV[2] familyId  ARGV[3] TTL(ms)
     * 반환 1
     * </pre>
     */
    static final RedisScript<Long> SAVE_NEW_FAMILY = RedisScript.of("""
        redis.call('HSET', KEYS[1], 'memberId', ARGV[1], 'familyId', ARGV[2])
        redis.call('PEXPIRE', KEYS[1], ARGV[3])
        redis.call('SET', KEYS[2], '1', 'PX', ARGV[3])
        return 1
        """, Long.class);

    /**
     * 회전 — 읽은 세션을 다시 확인하고 한 번에 판정한다. 반환은 {@code RefreshTokenRotation} 이름이다.
     *
     * <pre>
     * KEYS[1] 옛 토큰  KEYS[2] family  KEYS[3] 새 토큰
     * ARGV[1] memberId  ARGV[2] familyId  ARGV[3] 지금(epoch ms)  ARGV[4] 재사용 유예(ms)  ARGV[5] TTL(ms)  ARGV[6] 형제 상한
     * </pre>
     *
     * <ul>
     *   <li>옛 토큰이 없거나 · memberId · familyId 가 읽은 값과 다르거나 · family 가 없다 → {@code INVALID} (아무것도 바꾸지 않는다)</li>
     *   <li>rotatedAt 이 없다 → rotatedAt=지금, 새 토큰 저장, family TTL 연장 → {@code ROTATED}. 옛 토큰의 TTL 은 그대로다</li>
     *   <li>rotatedAt 이 있고 지금 − rotatedAt ≤ 유예(음수는 0 — 인스턴스 간 시계 차이) → siblings 를 하나 올리고,
     *       상한 안이면 새 토큰(형제) 저장 · family TTL 연장, <b>rotatedAt 은 그대로</b> → {@code REISSUED_IN_GRACE}</li>
     *   <li>유예 밖 · 형제 상한 초과 · rotatedAt 이 숫자가 아니다 → family 삭제 → {@code REUSE_DETECTED}</li>
     * </ul>
     * HMGET 은 없는 필드를 false 로 준다 — 문자열 ARGV 와 같을 수 없어 첫 줄이 없는 토큰까지 걸러 낸다.
     * 키 타입이 HASH 가 아니면(WRONGTYPE) 스크립트 오류다 — 어댑터가 저장소 장애(503)로 바꾼다.
     */
    static final RedisScript<String> ROTATE = RedisScript.of("""
        local stored = redis.call('HMGET', KEYS[1], 'memberId', 'familyId', 'rotatedAt')
        if stored[1] ~= ARGV[1] or stored[2] ~= ARGV[2] or redis.call('EXISTS', KEYS[2]) == 0 then
          return 'INVALID'
        end
        local result = 'ROTATED'
        if stored[3] then
          local rotatedAt = tonumber(stored[3])
          if rotatedAt == nil then
            redis.call('DEL', KEYS[2])
            return 'REUSE_DETECTED'
          end
          local elapsed = tonumber(ARGV[3]) - rotatedAt
          if elapsed < 0 then
            elapsed = 0
          end
          if elapsed > tonumber(ARGV[4]) then
            redis.call('DEL', KEYS[2])
            return 'REUSE_DETECTED'
          end
          if redis.call('HINCRBY', KEYS[1], 'siblings', 1) > tonumber(ARGV[6]) then
            redis.call('DEL', KEYS[2])
            return 'REUSE_DETECTED'
          end
          result = 'REISSUED_IN_GRACE'
        else
          redis.call('HSET', KEYS[1], 'rotatedAt', ARGV[3])
        end
        redis.call('HSET', KEYS[3], 'memberId', ARGV[1], 'familyId', ARGV[2])
        redis.call('PEXPIRE', KEYS[3], ARGV[5])
        redis.call('PEXPIRE', KEYS[2], ARGV[5])
        return result
        """, String.class);

    private RefreshTokenRedisScripts() {
    }
}
