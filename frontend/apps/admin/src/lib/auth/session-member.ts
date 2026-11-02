/**
 * 지금 세션 관리자의 회원 아이디 — access 토큰(JWT) payload 의 `sub`(BE `JwtIssuer`, memberId 문자열)를 읽기만 한다.
 * 서명은 검증하지 않는다. 화면에서 "내 계정" 줄을 가리는 힌트일 뿐이고 권한 · 본인 판정의 정본은 BE 다(동료 OTP 복구의 본인 대상은 `AUTH_045`).
 * 서버 컴포넌트에서만 부른다 — 토큰은 httpOnly 쿠키에 있고 브라우저 JS 로 넘기지 않는다. 넘기는 것은 이 아이디 하나다.
 * 읽을 수 없으면 null 이다(쿠키 없음 · 모양이 다름).
 */
export function memberIdOfToken(token: string | null | undefined): string | null {
  const payload = token?.split('.')[1];
  if (!payload) return null;
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const json = new TextDecoder().decode(Uint8Array.from(atob(base64), (c) => c.charCodeAt(0)));
    const sub: unknown = (JSON.parse(json) as { sub?: unknown }).sub;
    // Snowflake 아이디 — 숫자로 바꾸지 않고 서버가 준 글자 그대로 목록의 memberId 와 맞춘다
    return typeof sub === 'string' && /^\d{1,19}$/.test(sub) ? sub : null;
  } catch {
    return null;
  }
}
