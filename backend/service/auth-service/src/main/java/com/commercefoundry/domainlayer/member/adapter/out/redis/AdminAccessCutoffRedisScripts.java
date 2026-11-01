package com.commercefoundry.domainlayer.member.adapter.out.redis;

import org.springframework.data.redis.core.script.RedisScript;

/** 관리자 access 컷오프를 원자적으로 최댓값으로 올린다. 늦게 도착한 옛 쓰기가 새 컷오프를 되돌리지 못한다. */
final class AdminAccessCutoffRedisScripts {

    /** KEYS[1] 컷오프 키, ARGV[1] epoch millis, ARGV[2] TTL millis. 값 손상은 Redis 오류로 돌려 fail-closed 한다. */
    static final RedisScript<Long> ADVANCE = RedisScript.of("""
        local previous = redis.call('GET', KEYS[1])
        if previous then
          local old = tonumber(previous)
          if not old then
            return redis.error_reply('invalid admin access cutoff')
          end
          if old > tonumber(ARGV[1]) then
            if redis.call('PTTL', KEYS[1]) < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
          end
        end
        redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
        return 1
        """, Long.class);

    private AdminAccessCutoffRedisScripts() {
    }
}
