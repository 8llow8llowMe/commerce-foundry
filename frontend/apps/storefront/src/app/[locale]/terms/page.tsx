import type { Metadata } from 'next';
import { LegalPage, legalMetadata } from '@/components/legal/LegalPage';

export async function generateMetadata({
  searchParams,
}: PageProps<'/[locale]/terms'>): Promise<Metadata> {
  return legalMetadata('TERMS', await searchParams);
}

/** 이용약관 공개 페이지(#570). 게시본만 보이고, `?revision=N` 으로 이전 게시본을 본다 */
export default async function TermsPage({ searchParams }: PageProps<'/[locale]/terms'>) {
  return <LegalPage type="TERMS" params={await searchParams} />;
}
