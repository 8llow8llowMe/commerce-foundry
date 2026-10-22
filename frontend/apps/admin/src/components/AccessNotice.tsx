'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useSyncExternalStore } from 'react';
import { loginHref } from '@/lib/auth/return-path';
import type { AccessDenied } from '@/lib/api-errors';

/**
 * 어드민 API 가 401 · 403 일 때의 안내.
 * - 401 — 읽기에서 세션이 끝났다(만료 · 로그아웃 · 차단). 지금 화면으로 돌아오는 로그인 화면으로 바로 보낸다(#204).
 *   저장(쓰기) 401 은 여기 오지 않는다 — 입력을 지키려고 폼 오류로 안내한다(`SESSION_EXPIRED_ON_SAVE`).
 *   이동이 늦거나 막혀도 누를 수 있게 로그인 링크를 같이 둔다.
 * - 403 — 로그인은 됐지만 ADMIN 이 아닌 토큰이다. 관리자 계정으로 다시 로그인하라고 안내한다(자동 이동은 하지 않는다).
 */
export function AccessNotice({ denied }: { denied: AccessDenied }) {
  const router = useRouter();
  // 돌아올 경로는 브라우저에서만 안다 — 서버 렌더에서는 콘솔 홈으로 돌아오는 링크다
  const href = loginHref(useSyncExternalStore(subscribeNothing, currentPath, () => '/'));

  useEffect(() => {
    // 이동 주소는 effect 안에서 직접 읽는다 — 서버 snapshot('/')으로 그린 첫 커밋에서도 돌아올 경로를 잃지 않게
    if (denied === 'unauthorized') router.replace(loginHref(currentPath()));
  }, [denied, router]);

  return (
    <div role="status" className="rounded-md bg-muted px-5 py-6">
      <p className="font-medium">
        {denied === 'unauthorized' ? '로그인이 만료되었습니다.' : '관리자 권한이 없습니다.'}
      </p>
      <p className="mt-1 text-sm text-muted-fg">
        {denied === 'unauthorized'
          ? '로그인 화면으로 이동합니다. '
          : '관리자 계정으로 다시 로그인해 주세요. '}
        <Link href={href} className="text-fg underline underline-offset-4">
          로그인
        </Link>
      </p>
    </div>
  );
}

// 이 안내가 떠 있는 동안 주소는 바뀌지 않는다(바뀌면 화면이 새로 그려진다) — 구독할 것이 없다
const subscribeNothing = () => () => {};
const currentPath = () => window.location.pathname + window.location.search;
