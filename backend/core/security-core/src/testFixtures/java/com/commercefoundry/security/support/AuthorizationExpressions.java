package com.commercefoundry.security.support;

import com.commercefoundry.security.internal.InternalTokenFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code @PreAuthorize} 식의 모양 판정. 런타임 가드({@link ResourceServerSmokeTestSupport#handlersDeclareAuthorization})와
 * 정적 규칙({@link ControllerAuthorizationArchTestSupport})이 같은 판정을 쓰게 한 곳에 둔다 — 한쪽만 고쳐 두 가드가 다른 식을 통과시키지 않게.
 *
 * <p>SpEL 을 파싱하지 않고 문자열 모양만 본다. 그래서 "허용하는 모양" 을 좁게 정하고 그 밖은 전부 실패로 둔다 — 통과시키는 쪽이 틀리면 공개 API 가 된다.
 */
public final class AuthorizationExpressions {

    /** 허용 호출자 선언의 모양 — 서비스 권한만 나열한 {@code hasAuthority} · {@code hasAnyAuthority} 식 그 자체 ({@code security-guide.md} §9-4). */
    public static final Pattern SERVICE_CALLERS = Pattern.compile(
        "has(Any)?Authority\\(\\s*'" + InternalTokenFormat.AUTHORITY_PREFIX + "[a-z][a-z0-9-]*'(\\s*,\\s*'" + InternalTokenFormat.AUTHORITY_PREFIX + "[a-z][a-z0-9-]*')*\\s*\\)");

    /** 어드민 식의 머리 — 반드시 {@code hasRole('ADMIN')} 으로 시작한다. */
    private static final Pattern ADMIN_ROLE_HEAD = Pattern.compile("hasRole\\(\\s*'ADMIN'\\s*\\)");
    /** 머리 뒤에 붙일 수 있는 것은 {@code and} · {@code &&} 로 잇는 조건뿐이다 (SpEL 연산자는 대소문자를 가리지 않는다). */
    private static final Pattern AND_TAIL = Pattern.compile("(?i)(and(?=[\\s(!])|&&)");

    private AuthorizationExpressions() {
    }

    /** {@code hasAuthority('SERVICE_<id>')} · {@code hasAnyAuthority('SERVICE_<id>', ...)} 그 자체인가. 앞뒤 공백만 무시한다. */
    public static boolean isServiceCallersOnly(String expression) {
        return expression != null && SERVICE_CALLERS.matcher(expression.strip()).matches();
    }

    /**
     * ADMIN 이 필요조건인가 — {@code hasRole('ADMIN')} 이거나 {@code hasRole('ADMIN') and ...} 이다 ({@code security-guide.md} §5).
     *
     * <ul>
     *   <li>{@code and} 뒤에 괄호 밖(최상위) {@code or} · {@code ||} · {@code ?}(삼항 · 엘비스)가 있으면 실패다. SpEL 에서 {@code and} 가 {@code or} 보다 먼저 묶여
     *       {@code hasRole('ADMIN') and a or b} 는 {@code (ADMIN and a) or b} — ADMIN 이 아니어도 통과한다. 삼항도 {@code and} 보다 늦게 묶인다.</li>
     *   <li>{@code ?.}(안전 탐색)도 {@code ?} 로 보아 실패로 둔다 — 어드민 식에 쓸 일이 없고, 좁게 허용하는 쪽이 안전하다.</li>
     *   <li>괄호 안의 {@code or} 는 {@code and} 로 묶여 있어 ADMIN 이 여전히 필요조건이다 — 통과시킨다.</li>
     *   <li>{@code permitAll()} · {@code isAuthenticated()} · {@code hasAnyRole('ADMIN', ...)} · {@code hasAuthority('ROLE_ADMIN')} 은 실패다.
     *       뒤의 둘은 같은 뜻이 될 수 있어도 문서의 표기({@code hasRole('ADMIN')}) 하나로 맞춘다.</li>
     * </ul>
     */
    public static boolean requiresAdminRole(String expression) {
        if (expression == null) {
            return false;
        }
        String stripped = expression.strip();
        Matcher head = ADMIN_ROLE_HEAD.matcher(stripped);
        if (!head.lookingAt()) {
            return false;
        }
        String tail = stripped.substring(head.end()).strip();
        if (tail.isEmpty()) {
            return true;
        }
        Matcher and = AND_TAIL.matcher(tail);
        return and.lookingAt() && !widensAtTopLevel(tail.substring(and.end()));
    }

    /** 괄호 · 문자열 밖에 {@code or} · {@code ||} · {@code ?} 가 있는가. 괄호가 맞지 않아도 넓힌 것으로 본다(실패 쪽). */
    private static boolean widensAtTopLevel(String condition) {
        int depth = 0;
        boolean quoted = false;
        String lower = condition.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (quoted) {
                continue;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    return true;
                }
            } else if (depth == 0 && (c == '?' || c == '|' || isOrKeyword(lower, i))) {
                return true;
            }
        }
        return depth != 0 || quoted;
    }

    private static boolean isOrKeyword(String lower, int at) {
        if (!lower.startsWith("or", at)) {
            return false;
        }
        boolean startsWord = at == 0 || !Character.isJavaIdentifierPart(lower.charAt(at - 1));
        boolean endsWord = at + 2 == lower.length() || !Character.isJavaIdentifierPart(lower.charAt(at + 2));
        return startsWord && endsWord;
    }
}
