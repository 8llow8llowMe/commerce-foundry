package com.commercefoundry.redis.key;

import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Redis 키. 형식은 {@code {owner}:{domain}:{id...}} 다 — 예 {@code auth:refresh-token:4611686018427387904}
 * ({@code coding-conventions.md} §9-7).
 *
 * <ul>
 *   <li>{@code owner} — 키를 쓰는 서비스의 짧은 이름. {@code auth}, {@code order}, {@code gateway} 처럼 소문자 kebab.</li>
 *   <li>{@code domain} — 그 서비스 안의 용도. {@code refresh-token}, {@code cart} 처럼 소문자 kebab.</li>
 *   <li>{@code id} — 하나 이상. 비어 있지 않고 {@code :}·공백이 없어야 한다. 숫자·UUID·base64url 은 그대로 쓴다.</li>
 * </ul>
 *
 * <p>서비스들이 Redis 하나를 같이 쓴다. owner 로 네임스페이스를 나눠야 같은 이름의 키가 부딪히지 않고,
 * 한 서비스가 패턴으로 지운 키가 다른 서비스 것이 아니게 된다. 싱글 테넌트 템플릿이라(고객사마다 Redis 가 따로다)
 * 스토어 접두어는 붙이지 않는다. 서비스를 가로지르는 키(auth 가 쓰고 gateway 가 읽는 등)는 <b>쓰는 쪽</b>이 owner 다.
 *
 * <p>잘못된 키는 {@link IllegalArgumentException} 으로 거부한다. id 에 토큰·개인정보가 들어갈 수 있어 메시지에 값을 넣지 않는다.
 *
 * @param value Redis 에 그대로 쓰는 키 문자열
 */
public record RedisKey(String value) {

    private static final String SEPARATOR = ":";
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

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
        if (idPart == null || idPart.isEmpty()) {
            throw new IllegalArgumentException("Redis 키의 id 는 비어 있으면 안 됩니다");
        }
        if (idPart.chars().anyMatch(c -> c == ':' || Character.isWhitespace(c))) {
            throw new IllegalArgumentException("Redis 키의 id 에 ':' 나 공백을 쓸 수 없습니다");
        }
        return idPart;
    }
}
