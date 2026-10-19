/**
 * 로그인 뒤 돌아갈 앱 경로(`?next=`). 언어 접두어 없는 같은 사이트 경로만 받는다 —
 * 외부 주소 · 프로토콜 상대 주소(`//evil`) · 역슬래시 우회를 받으면 열린 리다이렉트가 된다.
 * 로그인 · 회원가입 화면으로 되돌아가는 경로도 메인으로 바꾼다.
 */
export function safeReturnPath(next: string | string[] | undefined): string {
  const value = Array.isArray(next) ? next[0] : next;
  if (!value || value.length > 512) return '/';
  if (!value.startsWith('/') || value.startsWith('//') || value.includes('\\')) return '/';
  // 제어 문자는 브라우저가 지워 '//' 가 될 수 있다
  if (/[\u0000-\u001f\u007f]/.test(value)) return '/';
  const path = value.split(/[?#]/)[0]!;
  if (path === '/login' || path === '/signup') return '/';
  return value;
}
