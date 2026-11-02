import type { InstagramLink } from '@cf/ui';
import { linkRel, safeHref } from '@/lib/display/home-sections';
import { MediaFrame } from './MediaFrame';

/**
 * 06 Our Story 패널 아래 인스타그램 줄(#59) — 글자 링크 + 정사각 사진 4장을 여백 없이 붙인 줄. 줄 전체가 계정으로 가는 링크 하나다
 * (사진마다 같은 곳으로 가는 링크를 두지 않는다). 사진은 꾸밈이라 링크 이름은 글자가 정한다. 주소가 https 가 아니면 그리지 않는다.
 */
export function InstagramRow({ instagram }: { instagram: InstagramLink }) {
  const href = safeHref(instagram.href);
  if (!href?.startsWith('https://')) return null;
  const photos = instagram.photos.slice(0, 4);

  return (
    // 스토어 밖 주소라 언어 접두어를 붙이는 i18n Link 가 아니라 <a> 다
    <a
      href={href}
      rel={linkRel(href)}
      className="group mx-2.5 mt-6 flex flex-col gap-4 lg:mx-5 lg:mt-8"
    >
      <div
        data-reveal="text"
        className="flex items-baseline justify-between gap-4 px-1 text-xs tracking-[0.2em] uppercase"
      >
        <span>Instagram</span>
        <span className="border-b border-current pb-1 normal-case tracking-[0.1em] group-hover:opacity-70">
          {instagram.handle} <span aria-hidden>↗</span>
        </span>
      </div>
      {photos.length > 0 && (
        <div aria-hidden className="grid grid-cols-4">
          {photos.map((photo, index) => (
            <MediaFrame
              key={index}
              slot={photo.image ? { ...photo, image: { ...photo.image, alt: '' } } : photo}
              className="aspect-square"
            />
          ))}
        </div>
      )}
    </a>
  );
}
