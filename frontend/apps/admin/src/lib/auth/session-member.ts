import { OPERATOR_ROLES, type OperatorRole } from './permissions';

/**
 * access 토큰(JWT) payload 를 읽기만 한다 — 서명은 검증하지 않는다. 읽을 수 없으면 null(쿠키 없음 · 모양이 다름).
 * 서버 컴포넌트에서만 부른다 — 토큰은 httpOnly 쿠키에 있고 브라우저 JS 로 넘기지 않는다. 넘기는 것은 여기서 꺼낸 값 하나다.
 */
function claimsOfToken(token: string | null | undefined): Record<string, unknown> | null {
  const payload = token?.split('.')[1];
  if (!payload) return null;
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const json = new TextDecoder().decode(Uint8Array.from(atob(base64), (c) => c.charCodeAt(0)));
    const claims: unknown = JSON.parse(json);
    return claims !== null && typeof claims === 'object'
      ? (claims as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

/**
 * 지금 세션 관리자의 회원 아이디 — payload 의 `sub`(BE `JwtIssuer`, memberId 문자열).
 * 화면에서 "내 계정" 줄을 가리는 힌트일 뿐이고 권한 · 본인 판정의 정본은 BE 다(동료 OTP 복구의 본인 대상은 `AUTH_045`).
 */
export function memberIdOfToken(token: string | null | undefined): string | null {
  const sub = claimsOfToken(token)?.sub;
  // Snowflake 아이디 — 숫자로 바꾸지 않고 서버가 준 글자 그대로 목록의 memberId 와 맞춘다
  return typeof sub === 'string' && /^\d{1,19}$/.test(sub) ? sub : null;
}

/**
 * 지금 세션 운영자의 역할 — payload 의 `role`(BE `MemberRole.CLAIM_NAME`, enum 이름 하나, #236).
 * 메뉴 · 화면 진입 안내를 고르는 힌트일 뿐이다. 인가의 정본은 게이트웨이 · 서비스의 `@PreAuthorize` 이고 FE 숨김은 보조다(#237).
 * 운영자 역할이 아니거나(고객 · 모르는 값) 읽을 수 없으면 null — 화면은 메뉴를 거르지 않고 서버 403 안내에 맡긴다.
 */
export function operatorRoleOfToken(token: string | null | undefined): OperatorRole | null {
  const role = claimsOfToken(token)?.role;
  return OPERATOR_ROLES.find((known) => known === role) ?? null;
}
