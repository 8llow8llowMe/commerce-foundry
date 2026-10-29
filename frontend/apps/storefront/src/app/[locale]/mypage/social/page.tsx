import type { Metadata } from 'next';
import { AccountShell } from '@/components/account/AccountShell';
import { SocialAccounts } from '@/components/account/SocialAccounts';
import { requireSession } from '@/lib/auth/require-session';
import { isSocialProvider } from '@/lib/auth/social';
import { getI18n } from '@/lib/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return { title: dict.auth.socialLink.title, robots: { index: false } };
}

/**
 * 간편 로그인 연결(#356 · BE #355). 연결 목록 · 비밀번호 재인증은 브라우저에서 BFF 로 부른다 — 만료된 access 를 BFF 가 재발급할 수 있게.
 * `?linked=<provider>` 는 연결 콜백이 성공 뒤 붙인다(완료 안내만, 연결 여부는 목록 응답으로 판단한다).
 */
export default async function SocialAccountsPage({
  searchParams,
}: PageProps<'/[locale]/mypage/social'>) {
  await requireSession('/mypage/social');
  const { dict } = await getI18n();
  const { linked } = await searchParams;
  return (
    <AccountShell title={dict.auth.socialLink.title} back={dict.account.back}>
      <SocialAccounts linked={isSocialProvider(linked) ? linked : null} />
    </AccountShell>
  );
}
