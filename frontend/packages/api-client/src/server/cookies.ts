// 인증 쿠키 이름·수명은 앱이 넘긴다. 고객은 #25, 어드민은 #204 에서 정했다.
type AccessCookie = {
  accessName: string;
  accessMaxAgeSec: number;
  /** 운영(https)에서는 true. 로컬 http 에서는 false. */
  secure: boolean;
};

/**
 * refresh 쿠키가 있으면(고객) BFF 가 재발급한다. `refreshName: null`(어드민 — 관리자 로그인은 refresh 를 주지 않는다, BE #191)이면
 * access 하나가 세션의 전부다 — 프록시 · 로그아웃은 refresh 쿠키를 읽지도 재발급하지도 않는다.
 */
export type AuthCookieConfig = AccessCookie &
  (
    | { refreshName: string; refreshMaxAgeSec: number }
    | { refreshName: null; refreshMaxAgeSec?: undefined }
  );

/** 설정에 refresh 쿠키가 없으면 null — 요청에 같은 이름의 쿠키가 있어도 보지 않는다 */
export function readRefreshCookie(
  cookieHeader: string | null,
  config: AuthCookieConfig,
): string | null {
  return config.refreshName === null ? null : readCookie(cookieHeader, config.refreshName);
}

export function readCookie(cookieHeader: string | null, name: string): string | null {
  if (!cookieHeader) return null;
  for (const part of cookieHeader.split(';')) {
    const index = part.indexOf('=');
    if (index === -1) continue;
    if (part.slice(0, index).trim() !== name) continue;
    try {
      return decodeURIComponent(part.slice(index + 1).trim());
    } catch {
      return null;
    }
  }
  return null;
}

export function serializeCookie(
  name: string,
  value: string,
  maxAgeSec: number,
  secure: boolean,
): string {
  const attributes = [
    `${name}=${encodeURIComponent(value)}`,
    'Path=/',
    'HttpOnly',
    'SameSite=Lax',
    `Max-Age=${maxAgeSec}`,
  ];
  if (secure) attributes.push('Secure');
  return attributes.join('; ');
}
