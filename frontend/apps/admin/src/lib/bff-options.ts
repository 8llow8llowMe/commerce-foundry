import { clientIpHeaderFromEnv } from '@cf/api-client/server';
import { authCookies } from './auth-cookies';
import { gatewayUrl } from './env';

/**
 * 서버 전용. BFF route handler(`app/api/**`) 공용 옵션. 서버 패키지(`node:net`)를 끌어오므로 `env.ts` 와 나눈다
 * — `env.ts` 는 proxy(미들웨어) · 레이아웃도 읽는다.
 * 클라이언트 IP 헤더는 `CLIENT_IP_HEADER`(기본 `x-real-ip`, `none` 이면 끔). 앞단(호스팅 플랫폼 · LB)이 브라우저 값을 버리고
 * 덮어쓰는 헤더여야 한다 — BFF 는 이 값 하나로 게이트웨이행 X-Forwarded-For 를 덮어쓴다.
 */
export const bffOptions = {
  gatewayUrl,
  cookies: authCookies,
  clientIpHeader: clientIpHeaderFromEnv(process.env.CLIENT_IP_HEADER),
};
