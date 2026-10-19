import type { DetailSection as DetailSectionData } from '@cf/ui';
import { CraftZoom } from './CraftZoom';
import { SectionHeading } from './SectionHeading';

/**
 * 05 Craft & Details (#59). 가운데 제목(Display) 아래 같은 드레스를 스크롤로 확대해 보는 무대(CraftZoom).
 * 스크립트가 없거나 전신 사진(frame)이 없으면 클로즈업과 제작 포인트를 세로로 놓는다.
 */
export function DetailSection({ section }: { section: DetailSectionData }) {
  const headingId = `${section.id}-title`;

  return (
    <section aria-labelledby={headingId}>
      <div className="mx-auto max-w-2xl px-4 text-center">
        <SectionHeading id={headingId} title={section.title} align="center" size="display" />
      </div>
      <CraftZoom
        frame={section.frame}
        points={section.points}
        closeups={section.media}
        intro={section.body}
      />
    </section>
  );
}
