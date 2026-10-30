'use client';

import { Button } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useTransition } from 'react';

/** 서버 컴포넌트 화면을 다시 읽는다(읽기 실패 뒤 "다시 불러오기"). 읽는 동안은 다시 누를 수 없다 */
export function RefreshButton({ children }: { children: string }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  return (
    <Button
      variant="secondary"
      disabled={pending}
      onClick={() => startTransition(() => router.refresh())}
    >
      {children}
    </Button>
  );
}
