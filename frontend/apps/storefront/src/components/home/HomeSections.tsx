import type { HomeBlock } from '@/lib/display/home-sections';
import { DetailSection } from './DetailSection';
import { HeroSection } from './HeroSection';
import { LookSection } from './LookSection';
import { ProductPickSection } from './ProductPickSection';
import { Reveal } from './Reveal';
import { StorySection } from './StorySection';

/** 메인 섹션을 데이터 순서대로 그린다. 섹션 구성 · 순서는 메인 전시 API(#63)가, API 를 못 쓰면 브랜드 설정이 정한다(loadHomeBlocks) */
export function HomeSections({ sections }: { sections: HomeBlock[] }) {
  return (
    // 섹션 간격 — 모바일 96 · 태블릿 120 · PC 160px. 섹션 안의 어떤 간격보다 크게(#57 §3)
    <div className="flex flex-col gap-24 pb-24 md:gap-30 md:pb-30 lg:gap-40 lg:pb-40">
      {sections.map((section) => {
        // 히어로는 첫 화면이라 바로 보이고, 나머지는 처음 보일 때 떠오른다(#57 §6)
        switch (section.type) {
          case 'HERO':
            return <HeroSection key={section.id} section={section} />;
          case 'PRODUCT_PICK':
            return (
              <Reveal key={section.id}>
                <ProductPickSection section={section} />
              </Reveal>
            );
          case 'STORY':
            return (
              <Reveal key={section.id}>
                <StorySection section={section} />
              </Reveal>
            );
          case 'LOOK':
            return (
              <Reveal key={section.id}>
                <LookSection section={section} />
              </Reveal>
            );
          case 'DETAIL':
            return (
              <Reveal key={section.id}>
                <DetailSection section={section} />
              </Reveal>
            );
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
