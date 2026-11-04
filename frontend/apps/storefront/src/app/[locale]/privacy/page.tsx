import type { Metadata } from 'next';
import { LegalPage, legalMetadata } from '@/components/legal/LegalPage';

export async function generateMetadata({
  searchParams,
}: PageProps<'/[locale]/privacy'>): Promise<Metadata> {
  return legalMetadata('PRIVACY', await searchParams);
}

/** 개인정보처리방침 공개 페이지(#570). 게시본만 보이고, `?revision=N` 으로 이전 게시본을 본다 */
export default async function PrivacyPage({ searchParams }: PageProps<'/[locale]/privacy'>) {
  return <LegalPage type="PRIVACY" params={await searchParams} />;
}
