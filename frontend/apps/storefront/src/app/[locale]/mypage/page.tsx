import type { Metadata } from 'next';
import { Link } from '@/components/i18n/Link';
import { requireSession } from '@/lib/auth/require-session';
import { getI18n } from '@/lib/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return { title: dict.account.title, robots: { index: false } };
}

/**
 * 마이페이지(#28) — 하위 화면으로 가는 메뉴. 주문 내역 · 배송 조회 · 취소 · 반품은 주문 조회 API(BE #16)가 들어오면 `/mypage/orders` 로 붙인다.
 * 로그인하지 않았으면 로그인 화면으로 보낸다.
 */
export default async function MyPage() {
  await requireSession('/mypage');
  const { dict } = await getI18n();
  const t = dict.account;
  const item = 'flex flex-col gap-1 bg-muted px-5 py-5';

  return (
    <main className="mx-auto w-full max-w-screen-sm px-4 py-12 lg:py-16">
      <h1 className="mb-8 font-serif text-[1.75rem] leading-tight">{t.title}</h1>
      <nav aria-label={t.nav}>
        <ul className="flex flex-col gap-2">
          <li className={item}>
            <span className="text-muted-fg">{t.orders}</span>
            <span className="text-sm text-muted-fg">{t.ordersPending}</span>
          </li>
          <li>
            <Link href="/mypage/profile" className={`${item} hover:text-primary`}>
              <span>{t.profileMenu}</span>
              <span className="text-sm text-muted-fg">{t.profileMenuNote}</span>
            </Link>
          </li>
          <li>
            <Link href="/mypage/addresses" className={`${item} hover:text-primary`}>
              <span>{t.addressMenu}</span>
              <span className="text-sm text-muted-fg">{t.addressMenuNote}</span>
            </Link>
          </li>
        </ul>
      </nav>
    </main>
  );
}
