import type { StorySection as StorySectionData } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import { MediaFrame } from './MediaFrame';
import { SectionHeading } from './SectionHeading';

/**
 * 03 Collection Story — curtain (#59). 짧은 글 뒤에 전폭 사진을 스크롤 고정(sticky)으로 쌓는다.
 * 같은 위치에 고정된 다음 장이 뒤에 오므로 스크롤하면 앞 장을 커튼처럼 덮는다 — 스크립트도, 스크롤 가로채기도 없다.
 * 고정은 사진 수(2~3장)만큼, 3화면 이내다. 캡션은 사진 아래 120px 의 어두운 스크림 위에만 얹는다(#57 §5 예외).
 */
export function CurtainStory({ section }: { section: StorySectionData }) {
  const headingId = `${section.id}-title`;

  return (
    <section aria-labelledby={headingId}>
      <div className="mx-auto max-w-screen-xl px-4 lg:px-8">
        <div className="flex w-3/5 flex-col gap-4 lg:w-[220px]">
          <SectionHeading id={headingId} title={section.title} />
          <p className="leading-7">{section.body}</p>
        </div>
      </div>

      <div className="mt-10 lg:mt-14">
        {section.media.map((slot, index) => (
          <figure
            key={index}
            className="sticky top-[var(--site-header-h)] h-[calc(100svh-var(--site-header-h))] w-full overflow-hidden"
          >
            <MediaFrame slot={slot} className="size-full" />
            {slot.caption && (
              <figcaption className="absolute inset-x-0 bottom-0 flex h-[120px] items-end bg-linear-to-t from-fg/60 to-transparent px-4 pb-6 text-[15px] text-bg lg:px-8">
                {slot.caption}
              </figcaption>
            )}
          </figure>
        ))}
      </div>

      {section.links.length > 0 && (
        <div className="mx-auto mt-10 flex max-w-screen-xl gap-6 px-4 lg:mt-14 lg:px-8">
          {section.links.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              className="border-b border-current pb-1 text-xs tracking-[0.2em] uppercase hover:opacity-70"
            >
              {link.label} <span aria-hidden>→</span>
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}
