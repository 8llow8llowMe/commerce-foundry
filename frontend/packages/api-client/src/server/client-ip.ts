import { isIP } from 'node:net';

/**
 * 클라이언트 IP 를 읽을 요청 헤더 기본값. 호스팅 플랫폼(Vercel 등) · nginx(`proxy_set_header X-Real-IP $remote_addr`)가
 * 브라우저 값을 버리고 접속 주소 한 값으로 덮어쓰는 헤더다. 앞단이 덮어쓰지 않는 구성이면 브라우저가 정할 수 있으니 바꾸거나 끈다.
 */
export const DEFAULT_CLIENT_IP_HEADER = 'x-real-ip';

const FORWARDED_FOR = 'x-forwarded-for';

/** 환경 변수 `CLIENT_IP_HEADER` 를 읽는다. 비면 기본값(`x-real-ip`), `none` 이면 끈다(null) */
export function clientIpHeaderFromEnv(value: string | undefined): string | null {
  const name = value?.trim().toLowerCase();
  if (!name) return DEFAULT_CLIENT_IP_HEADER;
  return name === 'none' ? null : name;
}

/**
 * 앞단이 정한 클라이언트 IP. `header` 가 null 이면 끈 것이다(IP 를 넘기지 않는다).
 * 값에 쉼표가 있으면(`X-Forwarded-For` 를 덧붙이는 L7 프록시) 맨 오른쪽 — 바로 앞 프록시가 붙인 값 — 만 쓴다. 맨 왼쪽은 브라우저가 정한다.
 * 주소가 아닌 값(호스트 이름 · 포트 · 대괄호)은 버린다 — 게이트웨이가 BFF 주소로 셀 뿐이다(닫힌 쪽).
 */
export function resolveClientIp(
  request: Request,
  header: string | null = DEFAULT_CLIENT_IP_HEADER,
): string | null {
  if (!header) return null;
  const raw = request.headers.get(header);
  if (raw === null) return null;
  const value = raw.split(',').at(-1)!.trim();
  return isIP(value) ? value : null;
}

/**
 * 게이트웨이행 `X-Forwarded-For` 를 클라이언트 IP 한 값으로 덮어쓴다. 모르면 지운다.
 * 게이트웨이는 BFF 송신 주소가 신뢰 프록시(`GATEWAY_TRUSTED_PROXIES`)일 때만 이 값을 읽는다(BE security-guide §7-3).
 */
export function setClientIp(headers: Headers, clientIp: string | null): Headers {
  if (clientIp) headers.set(FORWARDED_FOR, clientIp);
  else headers.delete(FORWARDED_FOR);
  return headers;
}
