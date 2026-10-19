import type { HeroSection as HeroSectionData } from '@cf/ui';
import Link from 'next/link';

/**
 * 01 Main Visual. 사이트에서 accent(Blush) 배경을 쓰는 몇 안 되는 구간이다.
 * 모바일은 글 → 세로 4:5 화보 순이라 첫 화면(375×667)에 메시지와 버튼이 들어온다. PC 는 글 | 가로 16:9 화보.
 */
export function HeroSection({ section }: { section: HeroSectionData }) {
  const { desktop, mobile, video } = section;
  const fallback = mobile.image ?? desktop.image;

  return (
    <section aria-labelledby={`${section.id}-title`} className="bg-accent text-accent-fg">
      <div className="mx-auto grid max-w-screen-xl lg:grid-cols-[2fr_3fr] lg:items-center">
        <div className="flex flex-col items-start gap-4 px-4 py-10 lg:px-12 lg:py-20">
          {section.eyebrow && (
            <p className="text-xs tracking-[0.25em] uppercase">{section.eyebrow}</p>
          )}
          <h1
            id={`${section.id}-title`}
            className="font-display text-4xl leading-tight lg:text-6xl"
          >
            {section.title.map((line, index) => (
              <span key={index} className="block">
                {line}
              </span>
            ))}
          </h1>
          {section.subtitle && (
            <p className="font-serif text-base lg:text-lg">{section.subtitle}</p>
          )}
          <Link
            href={section.cta.href}
            className="mt-4 border-b border-current pb-1 text-xs tracking-[0.2em] uppercase hover:opacity-70"
          >
            {section.cta.label} <span aria-hidden>↗</span>
          </Link>
        </div>

        <div className="relative aspect-[4/5] overflow-hidden bg-muted lg:aspect-video">
          {video ? (
            <>
              {/* 움직임 줄이기 설정이면 영상 대신 포스터 */}
              <video
                src={video.src}
                poster={video.poster}
                autoPlay
                muted
                loop
                playsInline
                aria-hidden
                className="absolute inset-0 size-full object-cover motion-reduce:hidden"
              />
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={video.poster}
                alt=""
                className="absolute inset-0 hidden size-full object-cover motion-reduce:block"
              />
            </>
          ) : fallback ? (
            <picture>
              {desktop.image && <source media="(min-width: 1024px)" srcSet={desktop.image.src} />}
              <img
                src={fallback.src}
                alt={fallback.alt}
                loading="eager"
                fetchPriority="high"
                decoding="async"
                className="absolute inset-0 size-full object-cover"
              />
            </picture>
          ) : (
            <span className="absolute inset-0 flex items-center justify-center p-4 text-center text-xs tracking-[0.1em] text-muted-fg">
              <span className="lg:hidden">{mobile.placeholder}</span>
              <span className="hidden lg:inline">{desktop.placeholder}</span>
            </span>
          )}
        </div>
      </div>
    </section>
  );
}
