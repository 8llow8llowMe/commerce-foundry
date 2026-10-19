import type { HomeSection } from '@cf/ui';
import { DetailSection } from './DetailSection';
import { HeroSection } from './HeroSection';
import { LookSection } from './LookSection';
import { ProductPickSection } from './ProductPickSection';
import { StorySection } from './StorySection';

/** 메인 섹션을 데이터 순서대로 그린다. 섹션 구성은 브랜드 설정(1차) 또는 메인 전시 API(#63)가 정한다 */
export function HomeSections({ sections }: { sections: HomeSection[] }) {
  return (
    <div className="flex flex-col gap-24 pb-24 lg:gap-36 lg:pb-36">
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
