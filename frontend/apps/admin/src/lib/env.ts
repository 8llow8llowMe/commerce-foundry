/** 서버 전용. 브라우저는 게이트웨이를 직접 부르지 않는다. */
export const gatewayUrl = process.env.GATEWAY_URL ?? 'http://localhost:8000';

/**
 * 서버 전용. 메인 전시 미리보기(#71)가 iframe 으로 여는 스토어프론트 origin(예: https://example.com).
 * 비우면 개발에서는 http://localhost:3000, 운영에서는 미리보기를 끈다. 스토어프론트 `ADMIN_ORIGIN` 과 짝이다.
 */
export const storefrontOrigin =
  process.env.STOREFRONT_ORIGIN ||
  (process.env.NODE_ENV === 'production' ? undefined : 'http://localhost:3000');
