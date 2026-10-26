import type { Metadata } from 'next';
import { MainPreview } from '@/components/home/MainPreview';
import { adminOrigin } from '@/lib/env';
import { parseOrigin } from '@/lib/display/preview';
import { getLocale } from '@/lib/i18n/server';
import { renderMainPreview } from './actions';

// 어드민 메인 전시 미리보기(#71). 어드민이 iframe 으로 열고 편집 상태를 postMessage 로 넘긴다 — 운영자 전용이라 검색에서 뺀다.
// iframe 허용(frame-ancestors)은 proxy 가 이 경로에만 건다.
export const metadata: Metadata = { robots: { index: false, follow: false } };

export default async function MainPreviewPage() {
  const locale = await getLocale();
  return (
    <main>
      <MainPreview
        locale={locale}
        adminOrigin={parseOrigin(adminOrigin)}
        render={renderMainPreview}
      />
    </main>
  );
}
