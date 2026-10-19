import { brand } from '@cf/brand';
import { HomeSections } from '@/components/home/HomeSections';

// 대표 상품 · 룩 구성품을 요청마다 받는다 — 캐시 전략은 메인 전시 API(#63)와 함께 정한다
export const dynamic = 'force-dynamic';

export default function HomePage() {
  return (
    <main>
      <HomeSections sections={brand.home} />
    </main>
  );
}
