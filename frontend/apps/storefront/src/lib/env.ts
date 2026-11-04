/** 서버 전용. 브라우저는 게이트웨이를 직접 부르지 않는다. */
export const gatewayUrl = process.env.GATEWAY_URL ?? 'http://localhost:8000';

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

/**
 * 서버 전용. 메인 전시 미리보기(#71)를 iframe 으로 여는 어드민 origin(예: https://admin.example.com).
 * `/preview/*` 의 `frame-ancestors` 와 postMessage 발신자 확인에 쓴다. 비우면 개발에서는 http://localhost:3001,
 * 운영에서는 미리보기를 닫는다(어느 사이트도 iframe 으로 열 수 없다). proxy 도 읽으므로 빌드 · 실행 모두에 같은 값을 준다.
 */
export const adminOrigin =
  process.env.ADMIN_ORIGIN ||
  (process.env.NODE_ENV === 'production' ? undefined : 'http://localhost:3001');

/**
 * 토스페이먼츠 결제위젯 클라이언트 키(`test_gck_` · `live_gck_`, 공개돼도 되는 키 — `backend/docs/payment-guide.md` §2). 빌드 시점에 들어간다(NEXT_PUBLIC_).
 * 서버 화면이 읽어 결제 화면에 넘긴다. 비었거나 결제위젯 키 형식이 아니면 결제위젯을 띄우지 않는다(`widgetClientKey`).
 */
export const tossClientKey = process.env.NEXT_PUBLIC_TOSS_CLIENT_KEY;
