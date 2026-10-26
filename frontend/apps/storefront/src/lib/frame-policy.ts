/** 같은 출처만 iframe 으로 감쌀 수 있다(클릭재킹 방어, #345). 스토어프론트 페이지의 기본값이다 */
export const SAME_ORIGIN_FRAME_POLICY = "frame-ancestors 'self'";

/**
 * 페이지 응답에 iframe 허용 범위를 싣는다. 정본은 CSP `frame-ancestors` 고, `X-Frame-Options` 는 그걸 모르는
 * 구형 브라우저용이다. XFO 는 특정 출처를 허용할 수 없으므로, 기본값이 아닌 정책(예: 어드민 미리보기 #71)에는 싣지 않는다
 * — 함께 실으면 CSP 를 모르는 브라우저가 그 출처까지 막는다.
 */
export function setFramePolicy(headers: Headers, policy: string = SAME_ORIGIN_FRAME_POLICY) {
  headers.set('Content-Security-Policy', policy);
  if (policy === SAME_ORIGIN_FRAME_POLICY) headers.set('X-Frame-Options', 'SAMEORIGIN');
  else headers.delete('X-Frame-Options');
}
