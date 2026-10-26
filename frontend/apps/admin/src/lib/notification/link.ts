import { resolveSitePath, safeReturnPath } from '@/lib/auth/return-path';

/** BE 가 알림 링크에 붙이는 어드민 접두어. 어드민 콘솔은 자기 origin 의 루트에서 돈다 */
const ADMIN_PREFIX = '/admin';

/**
 * 알림 `linkPath` → 콘솔 화면 경로. 없거나 쓸 수 없으면 null(링크 없이 보인다).
 *
 * - BE 는 어드민 화면을 `/admin/jobs/{jobId}` 처럼 `/admin` 아래로 적는다(`JobFinishedNotificationListener`). 콘솔은 origin 루트라
 *   맨 앞 `/admin` 한 마디를 떼어 `/jobs/{jobId}` 로 간다. `/admin` 이 없는 경로는 그대로 쓴다.
 * - 같은 사이트 경로만 받는다(`safeReturnPath` 와 같은 규칙) — 외부 주소 · `//` · 역슬래시 · 제어 문자 · `/login` · `/api/` 는 링크를 두지 않는다.
 */
export function consolePathOf(linkPath: string | null): string | null {
  if (!linkPath) return null;
  let path = linkPath;
  if (path === ADMIN_PREFIX) path = '/';
  else if (path.startsWith(`${ADMIN_PREFIX}/`) || path.startsWith(`${ADMIN_PREFIX}?`)) {
    path = path.slice(ADMIN_PREFIX.length);
    if (!path.startsWith('/')) path = `/${path}`;
  }
  // `.` · `..` 마디를 풀어 둔 뒤 검사한다(`/admin/../api/x` 가 `/api` 검사를 비켜 가지 않게)
  const resolved = resolveSitePath(path);
  if (resolved === null) return null;
  path = resolved;
  const safe = safeReturnPath(path);
  // 안전하지 않으면 safeReturnPath 가 홈('/')으로 바꾼다 — 원래 홈이 아니었으면 링크를 두지 않는다
  return safe === '/' && path !== '/' ? null : safe;
}
