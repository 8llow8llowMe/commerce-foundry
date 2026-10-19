import type { DetailSection as DetailSectionData } from '@cf/ui';
import { MediaFrame } from './MediaFrame';
import { SectionHeading } from './SectionHeading';

/** 05 Craft & Details. 가운데 제목 → 클로즈업 3칸 → 제작 포인트(번호 · 구분선 없이 여백으로 나눈다, #57) */
export function DetailSection({ section }: { section: DetailSectionData }) {
  const headingId = `${section.id}-title`;

  return (
    <section aria-labelledby={headingId} className="mx-auto w-full max-w-screen-xl px-4 lg:px-8">
      <div className="mx-auto flex max-w-2xl flex-col gap-5 text-center">
        <SectionHeading id={headingId} title={section.title} align="center" />
        <p className="font-serif leading-8">{section.body}</p>
      </div>

      {section.media.length > 0 && (
        <div className="mt-12 grid grid-cols-3 gap-2 lg:mt-16 lg:gap-4">
          {section.media.map((slot, index) => (
            <MediaFrame key={index} slot={slot} className="aspect-[3/4]" />
          ))}
        </div>
      )}

      {section.points.length > 0 && (
        <ul className="mt-12 grid gap-10 lg:mt-16 lg:grid-cols-3 lg:gap-12">
          {section.points.map((point) => (
            <li key={point.title} className="flex flex-col gap-2">
              <p className="font-serif text-lg">{point.title}</p>
              <p className="leading-7">{point.text}</p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
