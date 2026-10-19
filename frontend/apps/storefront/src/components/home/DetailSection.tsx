import type { DetailSection as DetailSectionData } from '@cf/ui';
import { MediaFrame } from './MediaFrame';
import { SectionHeading } from './SectionHeading';

/** 05 Craft & Details. 가운데 제목 → 클로즈업 3칸 → 번호 붙은 제작 포인트 */
export function DetailSection({ section }: { section: DetailSectionData }) {
  const headingId = `${section.id}-title`;

  return (
    <section aria-labelledby={headingId} className="mx-auto w-full max-w-screen-xl px-4 lg:px-8">
      <div className="mx-auto flex max-w-2xl flex-col gap-5 text-center">
        <SectionHeading
          id={headingId}
          eyebrow={section.eyebrow}
          title={section.title}
          align="center"
        />
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
        <ol className="mt-10 grid gap-8 lg:mt-14 lg:grid-cols-3">
          {section.points.map((point, index) => (
            <li key={point.title} className="flex flex-col gap-2 border-t border-border pt-5">
              <span aria-hidden className="font-display text-lg text-muted-fg">
                {String(index + 1).padStart(2, '0')}
              </span>
              <p className="text-sm font-medium">{point.title}</p>
              <p className="text-sm leading-7 text-muted-fg">{point.text}</p>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
