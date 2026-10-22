import type { Metadata } from 'next';
import { CheckoutView } from '@/components/checkout/CheckoutView';
import { policyCountryCodes } from '@/lib/account/countries';
import { requireSession } from '@/lib/auth/require-session';
import { getI18n } from '@/lib/i18n/server';
import { serverFetch } from '@/lib/server-api';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return { title: dict.checkout.title, robots: { index: false } };
}

/**
 * 주문서(#26 · BE #13 B2). 견적 API 가 로그인 회원만 받으므로 진입부터 로그인을 요구한다(`requireSession`).
 * 새 배송지의 국가 선택지는 해외 배송 정책의 국가다(배송지 관리와 같다). 장바구니 · 배송지 · 견적은 브라우저에서 BFF 로 부른다.
 */
export default async function CheckoutPage() {
  await requireSession('/checkout');
  const { dict } = await getI18n();
  const policy = await serverFetch<unknown>('/api/v1/shipping-fees/international-policy').catch(
    () => null,
  );
  return (
    <main className="mx-auto w-full max-w-screen-lg px-4 py-12 lg:px-8 lg:py-16">
      <h1 className="mb-8 font-serif text-[1.75rem] leading-tight">{dict.checkout.title}</h1>
      <CheckoutView shippableCountries={policyCountryCodes(policy)} />
    </main>
  );
}
