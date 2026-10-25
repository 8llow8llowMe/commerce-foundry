import type { HeroSection as HeroSectionData } from '@cf/ui';
import { cx } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import { linkRel } from '@/lib/display/home-sections';

/** 직각 + 단색 채움 버튼 모양의 링크(#57 버튼 한 모양) */
const ACTION =
  'inline-flex h-12 items-center justify-center px-6 text-sm tracking-[0.15em] uppercase transition hover:opacity-80';

/**
 * 01 Main Visual (#59 명세). Blush 단색 면 위에 세로 화보를 크게 — 사진을 상자에 넣지 않는다.
 * - PC: 면이 첫 화면을 채우고(100svh − 헤더), 화보는 오른쪽 52vw 로 아래쪽이 화면 밖으로 잘린다. 글은 왼쪽 아래.
 * - 모바일: 전폭 세로 화보(최대 62svh) → 제목 · 문구 → 반반 버튼 2개. 첫 화면 안에 버튼이 들어온다. 사진 위에 글을 올리지 않는다.
 * 제목은 줄마다 끊고 줄바꿈하지 않는다 — 어떤 폭에서도 2줄 이내(#57 §4). 한 줄 제목(메인 전시 API)은 폭에 맞춰 줄바꿈한다 —
 * 끊을 자리를 모르는 운영 문구를 한 줄로 두면 화면 밖으로 넘친다. 제목이 없으면 h1 을 그리지 않는다.
 */
export function HeroSection({ section }: { section: HeroSectionData }) {
  const titleId = `${section.id}-title`;

  return (
    <section
      aria-labelledby={section.title.length > 0 ? titleId : undefined}
      className="relative overflow-hidden bg-accent text-accent-fg lg:h-[calc(100svh-var(--site-header-h))] lg:min-h-[40rem]"
    >
      <div className="relative aspect-[4/5] max-h-[62svh] w-full overflow-hidden bg-muted lg:absolute lg:top-[6svh] lg:right-[4vw] lg:max-h-none lg:w-[52vw]">
        <HeroMedia section={section} />
      </div>

      <div className="flex flex-col items-start gap-4 px-4 pt-7 pb-8 lg:absolute lg:bottom-[8svh] lg:left-8 lg:max-w-[40vw] lg:gap-6 lg:p-0">
        {section.title.length > 0 && (
          <h1
            id={titleId}
            className="font-display text-[2.5rem] leading-none font-light tracking-[-0.02em] lg:text-[clamp(3rem,5vw,6rem)]"
          >
            {section.title.map((line, index) => (
              <span
                key={index}
                className={cx(
                  'block',
                  section.title.length > 1 ? 'whitespace-nowrap' : 'text-balance break-keep',
                )}
              >
                {line}
              </span>
            ))}
          </h1>
        )}
        {section.subtitle && <p className="font-serif text-xl lg:text-2xl">{section.subtitle}</p>}

        <div
          className={cx(
            'mt-2 grid w-full gap-2 lg:flex lg:w-auto lg:items-center lg:gap-8',
            section.secondaryCta ? 'grid-cols-2' : 'grid-cols-1',
          )}
        >
          <Link
            href={section.cta.href}
            rel={linkRel(section.cta.href)}
            className={cx(ACTION, 'bg-primary text-primary-fg')}
          >
            {section.cta.label}
          </Link>
          {section.secondaryCta && (
            // 모바일은 반반 버튼, PC 는 글자 링크(명세: [ 컬렉션 보기 ]  SHOP →)
            <Link
              href={section.secondaryCta.href}
              rel={linkRel(section.secondaryCta.href)}
              className={cx(
                ACTION,
                'bg-bg text-fg lg:h-auto lg:border-b lg:border-current lg:bg-transparent lg:px-0 lg:pb-1',
              )}
            >
              {section.secondaryCta.label}
              <span aria-hidden className="ml-2 hidden lg:inline">
                →
              </span>
            </Link>
          )}
        </div>
      </div>
    </section>
  );
}

/** 화보 · 영상 · 빈 자리. PC 와 모바일 사진은 <picture> 로 따로 받는다(둘 다 세로 4:5) */
function HeroMedia({ section }: { section: HeroSectionData }) {
  const { desktop, mobile, video } = section;
  const fallback = mobile.image ?? desktop.image;

  if (video) {
    return (
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
    );
  }
  if (fallback) {
    return (
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
    );
  }
  return (
    <span className="absolute inset-0 flex items-center justify-center p-4 text-center text-xs tracking-[0.1em] text-muted-fg">
      <span className="lg:hidden">{mobile.placeholder}</span>
      <span className="hidden lg:inline">{desktop.placeholder}</span>
    </span>
  );
}
