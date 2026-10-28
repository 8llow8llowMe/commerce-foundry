/**
 * 소셜 로그인 제공자 허용 목록(BE #5). 화면(시작 버튼 · 콜백 경로)과 BFF 소셜 로그인 핸들러가 같은 목록을 쓴다.
 * 브라우저에서도 import 하므로 서버 전용 진입점이 아니라 패키지 루트에서 내보낸다.
 */
export const SOCIAL_PROVIDERS = ['kakao', 'naver'] as const;
export type SocialProvider = (typeof SOCIAL_PROVIDERS)[number];

/** 허용 목록 값인가 — 대소문자 변형도 받지 않는다 */
export function isSocialProvider(value: unknown): value is SocialProvider {
  return typeof value === 'string' && (SOCIAL_PROVIDERS as readonly string[]).includes(value);
}
