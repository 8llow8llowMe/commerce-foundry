import type { Metadata } from 'next';
import { AccountShell } from '@/components/account/AccountShell';
import { AddressBook } from '@/components/account/AddressBook';
import { requireSession } from '@/lib/auth/require-session';
import { policyCountryCodes } from '@/lib/account/countries';
import { getI18n } from '@/lib/i18n/server';
import { serverFetch } from '@/lib/server-api';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return { title: dict.account.address.title, robots: { index: false } };
}

/**
 * 배송지 관리(#28 · BE #7). 국가 선택지는 해외 배송 정책의 국가다 — 해외 배송을 끄거나 정책을 읽지 못하면 국내만 고른다.
 * 주소록은 브라우저에서 BFF 로 읽고 쓴다(만료된 access 를 BFF 가 재발급할 수 있게).
 */
export default async function AddressesPage() {
  await requireSession('/mypage/addresses');
  const { dict } = await getI18n();
  const policy = await serverFetch<unknown>('/api/v1/shipping-fees/international-policy').catch(
    () => null,
  );
  return (
    <AccountShell title={dict.account.address.title} back={dict.account.back}>
      <AddressBook shippableCountries={policyCountryCodes(policy)} />
    </AccountShell>
  );
}
