import { NextResponse, type NextRequest } from 'next/server';
import { previewFramePolicy, parseOrigin } from '@/lib/display/preview';
import { adminOrigin } from '@/lib/env';
import { localeRouting } from '@/lib/i18n/config';
import { routeRequest } from '@/lib/i18n/routing';

/**
 * 바깥 URL(경로 접두어 · 언어 호스트)을 앱 안의 /[locale]/... 로 바꾼다. 규칙은 lib/i18n/routing.ts 다.
 * 브라우저 언어(Accept-Language)로 자동으로 보내지 않는다 — 같은 주소는 누구에게나 같은 언어다(검색 노출 · 공유 링크).
 */
export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  // 앞단 프록시 · 로드밸런서 뒤에서는 원래 호스트가 x-forwarded-host 에 있다. 언어 선택에만 쓴다
  const host = request.headers.get('x-forwarded-host') ?? request.headers.get('host');
  const decision = routeRequest(localeRouting, host, pathname, search);

  if (decision.type === 'redirect') {
    // 정식 주소가 바뀌지 않는 규칙이라 영구 이동(308, 메서드 유지)
    return NextResponse.redirect(new URL(decision.url, request.url), 308);
  }
  const url = request.nextUrl.clone();
  url.pathname = decision.pathname;
  const response = NextResponse.rewrite(url);
  // 메인 전시 미리보기(#71)만 어드민 iframe 에 열린다
  const framePolicy = previewFramePolicy(decision.pathname, parseOrigin(adminOrigin));
  if (framePolicy) response.headers.set('Content-Security-Policy', framePolicy);
  return response;
}

export const config = {
  // BFF(/api) · Next 내부(/_next) · 확장자가 있는 정적 파일은 언어와 무관하다
  matcher: ['/((?!api|_next|.*\\..*).*)'],
};
