package com.commercefoundry.domainlayer.member.adapter.out.redis;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;

/**
 * 토큰 저장소 어댑터가 같이 쓰는 Redis 오류 변환. 연결 실패 · 명령 타임아웃 · 스크립트 오류(WRONGTYPE 등)는 모두 {@link DataAccessException} 이고
 * 여기서 {@code AUTH_013}(503)으로 바꾼다. 로그에는 예외 타입만 남긴다 — 메시지에 키(지문 · jti)가 섞일 수 있다.
 */
@Slf4j
final class TokenStoreRedisSupport {

    private TokenStoreRedisSupport() {
    }

    static <T> T execute(Supplier<T> command) {
        try {
            return command.get();
        } catch (DataAccessException exception) {
            log.warn("token store unavailable: {}", exception.getClass().getName());
            throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE, exception);
        }
    }
}
