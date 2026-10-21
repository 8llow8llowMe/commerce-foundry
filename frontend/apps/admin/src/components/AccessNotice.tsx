import type { AccessDenied } from '@/lib/api-errors';

/**
 * 어드민 API 가 401 · 403 일 때의 안내. 어드민 로그인(#29 · BE #6)이 생기기 전에는 모든 화면이 이 상태다.
 * 401 은 로그인 필요, 403 은 로그인은 됐지만 ADMIN 이 아닌 계정이다.
 */
export function AccessNotice({ denied }: { denied: AccessDenied }) {
  return (
    <div role="status" className="rounded-md bg-muted px-5 py-6">
      <p className="font-medium">
        {denied === 'unauthorized' ? '관리자 로그인이 필요합니다.' : '관리자 권한이 없습니다.'}
      </p>
      <p className="mt-1 text-sm text-muted-fg">
        {denied === 'unauthorized'
          ? '관리자 계정으로 로그인한 뒤 다시 열어 주세요.'
          : '관리자 계정으로 다시 로그인해 주세요.'}
      </p>
    </div>
  );
}
