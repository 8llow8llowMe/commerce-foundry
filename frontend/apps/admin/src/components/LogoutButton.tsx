'use client';

import { apiFetch } from '@cf/api-client';
import { useRouter } from 'next/navigation';
import { useState } from 'react';

/**
 * 상단 바 로그아웃(#204). BFF `POST /api/v1/auth/logout` 은 서버 결과와 무관하게 쿠키를 지우고 200 이다.
 * 그 요청 자체가 실패하면(연결 끊김) 쿠키가 남아 있으므로 로그인 화면으로 가지 않고 버튼 옆에 알린다.
 */
export function LogoutButton() {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [failed, setFailed] = useState(false);

  const onClick = async () => {
    if (pending) return;
    setPending(true);
    setFailed(false);
    try {
      await apiFetch<null>('/api/v1/auth/logout', { method: 'POST' });
      router.replace('/login');
      router.refresh();
    } catch {
      setPending(false);
      setFailed(true);
    }
  };

  return (
    <div className="flex items-center gap-3">
      {failed && (
        <span role="alert" className="text-xs">
          로그아웃하지 못했습니다. 다시 시도해 주세요.
        </span>
      )}
      <button
        type="button"
        onClick={() => void onClick()}
        disabled={pending}
        className="rounded-md px-3 py-1.5 text-sm hover:bg-primary-fg/10 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-fg disabled:opacity-60"
      >
        {pending ? '로그아웃하는 중…' : '로그아웃'}
      </button>
    </div>
  );
}
