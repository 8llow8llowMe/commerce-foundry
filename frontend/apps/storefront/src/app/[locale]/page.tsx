import type { Metadata } from 'next';
import { HomeSections } from '@/components/home/HomeSections';
import { getI18n, localeAlternates } from '@/lib/i18n/server';

// 대표 상품 · 룩 구성품을 요청마다 받는다 — 캐시 전략은 메인 전시 API(#63)와 함께 정한다
export const dynamic = 'force-dynamic';

export async function generateMetadata(): Promise<Metadata> {
  return { alternates: await localeAlternates('/') };
}

export default async function HomePage() {
  const { content } = await getI18n();
  return (
    <main>
      <HomeSections sections={content.home} />
    </main>
  );
}
