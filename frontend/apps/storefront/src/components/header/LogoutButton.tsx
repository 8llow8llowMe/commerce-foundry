'use client';

import { apiFetch } from '@cf/api-client';
import { useRouter } from 'next/navigation';
import { useState, type ReactNode } from 'react';
import { useLocalizedHref } from '@/components/i18n/LocaleProvider';

/**
 * BFF `POST /api/v1/auth/logout` 은 서버 결과와 무관하게 쿠키를 지우고 200 이다. 요청이 아예 실패해도(연결 끊김)
 * 화면을 다시 그려 실제 쿠키 상태를 따른다 — 로그아웃된 척하지 않는다.
 */
export function LogoutButton({
  className,
  onDone,
  children,
}: {
  className?: string;
  onDone?: () => void;
  children: ReactNode;
}) {
  const router = useRouter();
  const localize = useLocalizedHref();
  const [pending, setPending] = useState(false);

  const onClick = async () => {
    if (pending) return;
    setPending(true);
    try {
      await apiFetch<null>('/api/v1/auth/logout', { method: 'POST' });
      onDone?.();
      router.replace(localize('/'));
    } catch {
      // 아래 refresh 로 실제 상태를 다시 읽는다
    } finally {
      router.refresh();
      setPending(false);
    }
  };

  return (
    <button type="button" onClick={onClick} disabled={pending} className={className}>
      {children}
    </button>
  );
}
