/** 돌아갈 경로로 받지 않는 인증 화면(그 아래 경로 포함) */
const AUTH_PAGES = ['/login', '/signup'];

/** 브라우저가 풀어 이동할 경로를 미리 구할 때 쓰는 가짜 origin */
const SITE_ORIGIN = 'http://site.invalid';

/**
 * 같은 사이트 경로를 브라우저가 이동할 모양으로 푼다(`.` · `..` 마디, `%2e%2e`). 같은 사이트 경로가 아니면 null.
 * `/` 로 시작하지 않거나 `//` · 역슬래시 · 제어 문자가 있으면 풀기 전에 막는다 — URL 해석이 그것들을 다른 주소로 바꿀 수 있어서다.
 * 검사는 푼 값으로 하고, 이동은 원래 값으로 한다(브라우저가 같은 규칙으로 풀므로 도착지는 검사한 경로다).
 * 어드민 `resolveSitePath` 와 같은 규칙이다.
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
 * 로그인 뒤 돌아갈 앱 경로(`?next=`). 언어 접두어 없는 같은 사이트 경로만 받는다 —
 * 외부 주소 · 프로토콜 상대 주소(`//evil`) · 역슬래시 우회를 받으면 열린 리다이렉트가 된다.
 * 로그인 · 회원가입 화면(그 아래 경로 포함)으로 되돌아가는 경로도 메인으로 바꾼다. `.` · `..` 마디를 풀어 둔 뒤 검사한다(#347).
 */
export function safeReturnPath(next: string | string[] | undefined): string {
  const value = Array.isArray(next) ? next[0] : next;
  if (!value || value.length > 512) return '/';
  const resolved = resolveSitePath(value);
  if (resolved === null) return '/';
  const path = resolved.split(/[?#]/)[0]!;
  // 그 아래 경로(소셜 로그인 콜백 `/login/oauth/{provider}` 등)도 — 로그인 뒤 콜백 화면으로 돌아가면 다시 시작 안내만 보인다
  if (AUTH_PAGES.some((page) => path === page || path.startsWith(`${page}/`))) return '/';
  return value;
}
