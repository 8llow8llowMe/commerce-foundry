// 인증 쿠키 이름·수명은 앱이 넘긴다. 정확한 값은 #25(고객) · #29(어드민)에서 확정한다.
export type AuthCookieConfig = {
  accessName: string;
  refreshName: string;
  accessMaxAgeSec: number;
  refreshMaxAgeSec: number;
  /** 운영(https)에서는 true. 로컬 http 에서는 false. */
  secure: boolean;
};

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
