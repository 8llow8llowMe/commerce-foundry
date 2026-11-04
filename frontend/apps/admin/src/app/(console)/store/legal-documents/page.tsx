import type { Metadata } from 'next';
import { LegalDocumentScreen } from '@/components/legal/LegalDocumentScreen';
import { parseLegalQuery } from '@/lib/legal/query';

export const metadata: Metadata = { title: '약관 · 개인정보처리방침' };

/**
 * 쇼핑몰 설정 > 약관 · 개인정보처리방침(#570). 편집할 문서(유형 · 언어)는 주소가 정본이다.
 * 문서를 바꾸면 화면 상태(입력 · 확인 단계)를 버리고 새로 읽는다.
 */
export default async function LegalDocumentsPage({
  searchParams,
}: PageProps<'/store/legal-documents'>) {
  const query = parseLegalQuery(await searchParams);
  return <LegalDocumentScreen key={`${query.type}-${query.locale}`} query={query} />;
}
