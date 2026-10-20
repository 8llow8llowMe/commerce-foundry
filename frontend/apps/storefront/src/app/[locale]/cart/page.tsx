import type { Metadata } from 'next';
import { CartView } from '@/components/cart/CartView';
import { getI18n, localeAlternates } from '@/lib/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return {
    title: dict.cart.title,
    alternates: await localeAlternates('/cart'),
    robots: { index: false },
  };
}

/** 장바구니(#26). 회원은 서버 장바구니, 비회원은 이 브라우저의 로컬 장바구니다 */
export default async function CartPage() {
  const { dict } = await getI18n();
  return (
    <main className="mx-auto w-full max-w-screen-lg px-4 py-12 lg:px-8 lg:py-16">
      <h1 className="mb-8 font-serif text-[1.75rem] leading-tight">{dict.cart.title}</h1>
      <CartView />
    </main>
  );
}
