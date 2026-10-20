import type { Metadata } from 'next';
import { AccountShell } from '@/components/account/AccountShell';
import { ProfileForm } from '@/components/account/ProfileForm';
import { requireSession } from '@/lib/auth/require-session';
import { getI18n } from '@/lib/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return { title: dict.account.profile.title, robots: { index: false } };
}

/** 회원 정보 수정(#28). 내 정보는 브라우저에서 BFF 로 읽는다 — 만료된 access 를 BFF 가 재발급할 수 있게 */
export default async function ProfilePage() {
  await requireSession('/mypage/profile');
  const { dict } = await getI18n();
  return (
    <AccountShell title={dict.account.profile.title} back={dict.account.back}>
      <ProfileForm />
    </AccountShell>
  );
}
