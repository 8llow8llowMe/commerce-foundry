/**
 * 로그인 뒤 돌아갈 콘솔 경로(`?next=`). 같은 사이트 경로만 받는다 — 외부 주소 · 프로토콜 상대 주소(`//evil`) ·
 * 역슬래시 우회를 받으면 열린 리다이렉트가 된다. 로그인 화면으로 되돌아가는 경로는 콘솔 홈으로 바꾼다.
 * 스토어프론트 `safeReturnPath` 와 같은 규칙이다(어드민은 언어 접두어 · 가입 화면이 없다).
 */
export function safeReturnPath(next: string | string[] | undefined): string {
  const value = Array.isArray(next) ? next[0] : next;
  if (!value || value.length > 512) return '/';
  if (!value.startsWith('/') || value.startsWith('//') || value.includes('\\')) return '/';
  // 제어 문자는 브라우저가 지워 '//' 가 될 수 있다
  if (/[\u0000-\u001f\u007f]/.test(value)) return '/';
  const path = value.split(/[?#]/)[0]!;
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
