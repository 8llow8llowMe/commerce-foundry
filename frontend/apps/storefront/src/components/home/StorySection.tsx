import type { StorySection as StorySectionData } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import { MediaFrame } from './MediaFrame';
import { SectionHeading } from './SectionHeading';

/**
 * 03 Collection Story · 06 Our Story. asymmetric 은 큰 화보 + 글 + 작은 컷을 어긋나게,
 * centered 는 글을 가운데 두고 아래에 넓은 사진 한 장. 같은 '왼쪽 글 / 오른쪽 이미지' 반복을 피한다.
 */
export function StorySection({ section }: { section: StorySectionData }) {
  const headingId = `${section.id}-title`;
  const [first, second] = section.media;

  const text = (
    <div className="flex flex-col gap-5">
      <SectionHeading
        id={headingId}
        title={section.title}
        align={section.layout === 'centered' ? 'center' : 'left'}
      />
      <p className="font-serif text-base leading-8 whitespace-pre-line">{section.body}</p>
      {section.links.length > 0 && (
        <div className={section.layout === 'centered' ? 'flex justify-center gap-6' : 'flex gap-6'}>
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
    </div>
  );

  if (section.layout === 'centered') {
    return (
      <section aria-labelledby={headingId} className="mx-auto w-full max-w-screen-xl px-4 lg:px-8">
        <div className="mx-auto max-w-xl text-center">{text}</div>
        {first && <MediaFrame slot={first} className="mt-12 aspect-[3/2] lg:mt-16" />}
      </section>
    );
  }

  return (
    <section
      aria-labelledby={headingId}
      className="mx-auto grid w-full max-w-screen-xl gap-8 px-4 lg:grid-cols-12 lg:gap-x-8 lg:px-8"
    >
      {first && <MediaFrame slot={first} className="aspect-[3/4] lg:col-span-7 lg:row-span-2" />}
      <div className="lg:col-span-4 lg:col-start-9 lg:self-end">{text}</div>
      {second && (
        <MediaFrame
          slot={second}
          className="aspect-[3/4] w-2/3 justify-self-end lg:col-span-3 lg:col-start-9 lg:w-full"
        />
      )}
    </section>
  );
}
