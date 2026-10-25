import type { Metadata } from 'next';
import { HomeSections } from '@/components/home/HomeSections';
import { loadHomeBlocks } from '@/lib/display/load-home';
import { localeAlternates } from '@/lib/i18n/server';

// 화면은 요청마다 그린다(레이아웃이 세션 쿠키를 읽는다). 메인 전시 응답만 서버 데이터 캐시에 60초 둔다
// (loadHomeBlocks) — force-dynamic 을 두면 그 캐시까지 꺼진다. 대표 상품(설정 기본) · 룩 구성품 조회는 캐시하지 않는다.

export async function generateMetadata(): Promise<Metadata> {
  return { alternates: await localeAlternates('/') };
}

export default async function HomePage() {
  const sections = await loadHomeBlocks();
  return (
    <main>
      <HomeSections sections={sections} />
    </main>
  );
}
