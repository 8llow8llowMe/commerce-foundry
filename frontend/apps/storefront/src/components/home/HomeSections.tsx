import type { HomeSection } from '@cf/ui';
import { DetailSection } from './DetailSection';
import { HeroSection } from './HeroSection';
import { LookSection } from './LookSection';
import { ProductPickSection } from './ProductPickSection';
import { StorySection } from './StorySection';

/** 메인 섹션을 데이터 순서대로 그린다. 섹션 구성은 브랜드 설정(1차) 또는 메인 전시 API(#63)가 정한다 */
export function HomeSections({ sections }: { sections: HomeSection[] }) {
  return (
    // 섹션 간격 — 모바일 96 · 태블릿 120 · PC 160px. 섹션 안의 어떤 간격보다 크게(#57 §3)
    <div className="flex flex-col gap-24 pb-24 md:gap-30 md:pb-30 lg:gap-40 lg:pb-40">
      {sections.map((section) => {
        switch (section.type) {
          case 'HERO':
            return <HeroSection key={section.id} section={section} />;
          case 'PRODUCT_PICK':
            return <ProductPickSection key={section.id} section={section} />;
          case 'STORY':
            return <StorySection key={section.id} section={section} />;
          case 'LOOK':
            return <LookSection key={section.id} section={section} />;
          case 'DETAIL':
            return <DetailSection key={section.id} section={section} />;
          default: {
            // 새 섹션 유형을 더하면 여기서 컴파일 오류가 난다
            const unknown: never = section;
            return unknown;
          }
        }
      })}
    </div>
  );
}
