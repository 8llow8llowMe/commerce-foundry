package com.commercefoundry.domainlayer.member.adapter.out.redis;

import com.commercefoundry.redis.key.RedisKey;

/**
 * refresh 토큰 키 ({@code coding-conventions.md} §9-7). auth 안에서만 읽고 쓰는 키라 auth 의 이 함수 하나로만 만든다.
 *
 * <ul>
 *   <li>{@code auth:refresh-token:{fingerprint}} — HASH {@code {memberId, familyId, rotatedAt?, siblings?}}. 원문이 아니라 SHA-256 지문(base64url 43자)이다.</li>
 *   <li>{@code auth:refresh-family:{familyId}} — STRING {@code "1"}. 있으면 그 family 의 토큰이 살아 있다. 지우면 모두 무효다.</li>
 * </ul>
 *
 * <p>TTL 은 둘 다 refresh 토큰 TTL 이고 쓰는 스크립트 안에서 함께 준다 ({@link RefreshTokenRedisScripts}).
 */
final class RefreshTokenRedisKeys {

    private static final String OWNER = "auth";

    private RefreshTokenRedisKeys() {
    }

    static RedisKey token(String fingerprint) {
        return RedisKey.of(OWNER, "refresh-token", fingerprint);
    }

    static RedisKey family(String familyId) {
        return RedisKey.of(OWNER, "refresh-family", familyId);
    }
}
