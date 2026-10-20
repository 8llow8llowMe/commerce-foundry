import type { ReactNode } from 'react';
import { Link } from '@/components/i18n/Link';

/** 마이페이지 하위 화면의 틀 — 마이페이지로 돌아가는 링크와 제목 */
export function AccountShell({
  title,
  back,
  children,
}: {
  title: string;
  back: string;
  children: ReactNode;
}) {
  return (
    <main className="mx-auto w-full max-w-screen-sm px-4 py-12 lg:py-16">
      <Link href="/mypage" className="text-sm text-muted-fg underline-offset-4 hover:underline">
        ← {back}
      </Link>
      <h1 className="mt-4 mb-8 font-serif text-[1.75rem] leading-tight">{title}</h1>
      {children}
    </main>
  );
}
