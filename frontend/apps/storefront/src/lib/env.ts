import { parseBusinessInfo } from './business-info';

/** 서버 전용. 브라우저는 게이트웨이를 직접 부르지 않는다. */
export const gatewayUrl = process.env.GATEWAY_URL ?? 'http://localhost:8000';

/**
 * 서버 전용. 푸터 사업자 정보(BUSINESS_*). 정적으로 미리 그리는 페이지(not-found 등)는 빌드 시점 값이 들어가므로
 * 배포 환경은 빌드 · 실행 모두에 같은 값을 준다.
 */
export const businessInfo = parseBusinessInfo(process.env);

/**
 * 서버 전용. 언어 전용 호스트(`en=en.example.com`). 없으면 모든 언어가 경로 방식(/en/...)이다.
 * proxy 도 읽으므로 빌드 · 실행 모두에 같은 값을 준다.
 */
export const localeHosts = process.env.LOCALE_HOSTS;

/** 기본 호스트의 정식 주소(끝 슬래시 없이) — hreflang · canonical 의 절대 주소. 빌드 시점에 들어간다(NEXT_PUBLIC_) */
export const siteUrl = (process.env.NEXT_PUBLIC_SITE_URL || 'http://localhost:3000').replace(
  /\/+$/,
  '',
);
