import type { Metadata } from 'next';
import { getI18n, localeAlternates } from '@/lib/i18n/server';
import { parseRevision, type LegalType } from '@/lib/legal/document';
import { loadLegalDocument } from '@/lib/legal/load';
import { LegalDocumentView } from './LegalDocumentView';

// 약관(`/terms`) · 개인정보처리방침(`/privacy`) 두 페이지가 같이 쓴다. 푸터 링크는 브랜드 설정 `footerLinks` 다.

type SearchParams = Record<string, string | string[] | undefined>;

const PATHS: Record<LegalType, string> = { TERMS: '/terms', PRIVACY: '/privacy' };

/**
 * 정식 주소는 현재 게시본(`/terms`) 하나다. 이전 게시본 · 게시본 없음 · 실패는 검색에 올리지 않는다(noindex).
 */
export async function legalMetadata(type: LegalType, params: SearchParams): Promise<Metadata> {
  const { locale, dict } = await getI18n();
  const revision = parseRevision(params.revision);
  const load = await loadLegalDocument(type, locale, revision);
  const name = type === 'TERMS' ? dict.legal.terms : dict.legal.privacy;
  return {
    title: name,
    alternates: await localeAlternates(PATHS[type]),
    ...(revision !== null || load.kind !== 'ok' ? { robots: { index: false } } : {}),
  };
}

export async function LegalPage({ type, params }: { type: LegalType; params: SearchParams }) {
  const { locale, dict, href } = await getI18n();
  const revision = parseRevision(params.revision);
  const path = PATHS[type];
  return (
    <LegalDocumentView
      name={type === 'TERMS' ? dict.legal.terms : dict.legal.privacy}
      path={path}
      formAction={href(path)}
      locale={locale}
      revision={revision}
      load={await loadLegalDocument(type, locale, revision)}
      labels={dict.legal}
    />
  );
}
