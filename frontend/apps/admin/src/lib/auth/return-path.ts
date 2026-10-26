/** 브라우저가 풀어 이동할 경로를 미리 구할 때 쓰는 가짜 origin */
const SITE_ORIGIN = 'http://site.invalid';

/**
 * 같은 사이트 경로를 브라우저가 이동할 모양으로 푼다(`.` · `..` 마디, `%2e%2e`). 같은 사이트 경로가 아니면 null.
 * `/` 로 시작하지 않거나 `//` · 역슬래시 · 제어 문자가 있으면 풀기 전에 막는다 — URL 해석이 그것들을 다른 주소로 바꿀 수 있어서다.
 * 검사는 푼 값으로 하고, 이동은 원래 값으로 한다(브라우저가 같은 규칙으로 풀므로 도착지는 검사한 경로다).
 */
export function resolveSitePath(value: string): string | null {
  if (!value.startsWith('/') || value.startsWith('//') || value.includes('\\')) return null;
  // 제어 문자는 브라우저가 지워 '//' 가 될 수 있다
  if (/[\u0000-\u001f\u007f]/.test(value)) return null;
  try {
    const url = new URL(value, SITE_ORIGIN);
    return url.origin === SITE_ORIGIN ? url.pathname + url.search + url.hash : null;
  } catch {
    return null;
  }
}

/**
 * 로그인 뒤 돌아갈 콘솔 경로(`?next=`). 같은 사이트 경로만 받는다 — 외부 주소 · 프로토콜 상대 주소(`//evil`) ·
 * 역슬래시 우회를 받으면 열린 리다이렉트가 된다. 로그인 화면 · BFF(`/api`)로 가는 경로는 콘솔 홈으로 바꾼다.
 * `.` · `..` 마디를 풀어 둔 뒤 검사한다(`/x/../login` 이 검사를 비켜 가지 않게, #347).
 * 스토어프론트 `safeReturnPath` 와 같은 규칙이다(어드민은 언어 접두어 · 가입 화면이 없다).
 */
export function safeReturnPath(next: string | string[] | undefined): string {
  const value = Array.isArray(next) ? next[0] : next;
  if (!value || value.length > 512) return '/';
  const resolved = resolveSitePath(value);
  if (resolved === null) return '/';
  const path = resolved.split(/[?#]/)[0]!;
  // 대소문자 · 끝 슬래시 · 경로 매개변수(;) 변형도 로그인 화면으로 본다
  const normalized = path.toLowerCase().split(';')[0]!.replace(/\/+$/, '');
  if (normalized === '/login' || normalized === '/api' || normalized.startsWith('/api/'))
    return '/';
  return value;
}

/** 지금 화면으로 돌아오는 로그인 화면 주소. 콘솔 홈이면 `next` 를 붙이지 않는다 */
export function loginHref(returnPath: string): string {
  const path = safeReturnPath(returnPath);
  return path === '/' ? '/login' : `/login?next=${encodeURIComponent(path)}`;
}
