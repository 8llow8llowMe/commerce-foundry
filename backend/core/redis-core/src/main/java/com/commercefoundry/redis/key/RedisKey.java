package com.commercefoundry.redis.key;

import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Redis 키. 형식은 {@code {owner}:{domain}:{id...}} 다 — 예 {@code auth:refresh-token:n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg}
 * (refresh 토큰 원문이 아니라 SHA-256 핑거프린트, base64url 43자), {@code order:stock-lock:4611686018427387904:2} ({@code coding-conventions.md} §9-7).
 *
 * <ul>
 *   <li>{@code owner} — 키의 수명주기(생성·갱신·삭제)를 소유하는 서비스의 짧은 이름. {@code auth}, {@code order}, {@code gateway} 처럼 소문자 kebab.</li>
 *   <li>{@code domain} — 그 서비스 안의 용도. {@code refresh-token}, {@code cart} 처럼 소문자 kebab.</li>
 *   <li>{@code id} — 하나 이상. ASCII {@code [A-Za-z0-9._~=+/-]} 1~128자. 숫자·UUID·base64(표준·url)는 그대로 쓴다.
 *       {@code :} 가 들어가는 값(IPv6 등)은 정규화한 뒤 인코딩한다.</li>
 * </ul>
 *
 * <p>서비스들이 Redis 하나를 같이 쓴다. owner 로 네임스페이스를 나눠 같은 이름의 키가 부딪히지 않게 한다.
 * 싱글 테넌트 템플릿이라(고객사마다 Redis 가 따로다) 스토어 접두어는 붙이지 않는다.
 * 다른 서비스가 읽는 키도 쓰기는 owner 의 API 를 거치고, 키는 양쪽이 의존하는 모듈의 키 함수 하나로만 만든다.
 *
 * <p>id 에 원문 토큰·개인정보를 넣지 않는다(해시로 넣는다) — {@link #toString()} 이 키 전체를 출력한다.
 * 잘못된 키는 {@link IllegalArgumentException} 으로 거부하고, 메시지에 값을 넣지 않는다.
 *
 * @param value Redis 에 그대로 쓰는 키 문자열
 */
public record RedisKey(String value) {

    private static final String SEPARATOR = ":";
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

    /** glob({@code * ? [ ] \})·해시 태그({@code { }})·공백·제어문자·비ASCII 를 막는다. 상한은 base64 SHA-256(43자)·UUID(36자)에 여유를 둔 값. */
    private static final Pattern ID_PART = Pattern.compile("[A-Za-z0-9._~=+/-]{1,128}");

    public RedisKey {
        if (value == null) {
            throw new IllegalArgumentException("Redis 키가 null 입니다");
        }
        String[] segments = value.split(SEPARATOR, -1);
        if (segments.length < 3) {
            throw new IllegalArgumentException("Redis 키는 {owner}:{domain}:{id...} 형식이어야 합니다");
        }
        requireName("owner", segments[0]);
        requireName("domain", segments[1]);
        for (int i = 2; i < segments.length; i++) {
            requireIdPart(segments[i]);
        }
    }

    public static RedisKey of(String owner, String domain, String... idParts) {
        if (idParts == null || idParts.length == 0) {
            throw new IllegalArgumentException("Redis 키에 id 가 하나 이상 필요합니다");
        }
        StringJoiner key = new StringJoiner(SEPARATOR)
            .add(requireName("owner", owner))
            .add(requireName("domain", domain));
        for (String idPart : idParts) {
            key.add(requireIdPart(idPart));
        }
        return new RedisKey(key.toString());
    }

    private static String requireName(String label, String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Redis 키의 " + label + " 는 소문자 kebab([a-z][a-z0-9-]*)이어야 합니다");
        }
        return name;
    }

    private static String requireIdPart(String idPart) {
        if (idPart == null || !ID_PART.matcher(idPart).matches()) {
            throw new IllegalArgumentException("Redis 키의 id 는 [A-Za-z0-9._~=+/-] 1~128자여야 합니다");
        }
        return idPart;
    }
}
