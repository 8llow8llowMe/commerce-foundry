import type { StorySection as StorySectionData } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import { MediaFrame } from './MediaFrame';

/**
 * 06 Our Story — panel (#59). 사이트에서 유일한 어두운 면(Soft Black)에 밝은 글.
 * 좌: 제목(2줄 이내) + 브랜드 모티프 모양(--cf-motif-radius, MOKOYURI 는 배래 곡선)으로 자른 인물 사진,
 * 우: 단락(본문을 빈 줄로 나눈다) + 버튼. 사진은 아래 1/5(모바일 1/4)이 패널 끝에서 잘린다.
 */
export function PanelStory({ section }: { section: StorySectionData }) {
  const headingId = `${section.id}-title`;
  const [photo] = section.media;
  const paragraphs = section.body.split(/\n{2,}/);

  return (
    <section aria-labelledby={headingId} className="mx-2.5 overflow-hidden bg-fg text-bg lg:mx-5">
      <div className="mx-auto grid max-w-screen-xl gap-10 px-6 pt-16 lg:min-h-[110svh] lg:grid-cols-12 lg:grid-rows-[auto_1fr] lg:gap-x-8 lg:px-12 lg:pt-24">
        <h2
          data-reveal="title"
          id={headingId}
          className="font-serif text-[1.75rem] leading-[1.25] lg:col-span-5 lg:text-[2.75rem]"
        >
          {section.title}
        </h2>

        <div className="flex flex-col gap-6 lg:col-span-4 lg:col-start-9 lg:row-span-2">
          {paragraphs.map((paragraph, index) => (
            <p key={index} data-reveal="text" className="leading-7">
              {paragraph}
            </p>
          ))}
          {section.links.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              className="inline-flex h-12 items-center justify-center self-start bg-bg px-6 text-sm tracking-[0.15em] text-fg uppercase transition hover:opacity-80"
            >
              {link.label}
            </Link>
          ))}
        </div>

        {photo && (
          <div className="w-[86vw] max-w-full translate-y-1/4 self-end lg:col-span-5 lg:row-start-2 lg:w-[38vw] lg:max-w-none lg:translate-y-1/5">
            <MediaFrame
              slot={photo}
              className="aspect-[3/4] [border-radius:var(--cf-motif-radius)]"
            />
          </div>
        )}
      </div>
    </section>
  );
}
