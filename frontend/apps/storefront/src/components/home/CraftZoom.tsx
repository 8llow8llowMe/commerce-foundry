'use client';

import type { DetailPoint, HomeMediaSlot } from '@cf/ui';
import { cx } from '@cf/ui';
import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { useDictionary } from '@/components/i18n/LocaleProvider';
import { MediaFrame } from './MediaFrame';

const noop = () => () => {};
/** 서버 렌더 · 하이드레이션 전에는 false — 그동안은 스크립트 없는 화면(클로즈업)을 그린다 */
const useHydrated = () =>
  useSyncExternalStore(
    noop,
    () => true,
    () => false,
  );

/** 스크롤 위치(0~1) → 단계. 0 은 전신, 1.. 은 포인트 */
export function craftStep(progress: number, steps: number): number {
  if (steps <= 1) return 0;
  const clamped = Math.min(Math.max(progress, 0), 0.9999);
  return Math.floor(clamped * steps);
}

/**
 * 05 Craft & Details 무대 (#59). 같은 드레스 전신 한 장을 스크롤에 따라 단계마다 확대한다(전신 → 포인트들).
 * 래퍼 높이(단계 × 75svh) 동안 무대가 헤더 아래에 고정되고, 스크롤 위치로 단계를 고른다. 사진 위 지점을 눌러도 그 단계로 간다.
 * 움직임은 transform 만 쓰고, 움직임 줄이기 설정이면 전환 없이 바로 바뀐다.
 * 스크립트가 없을 때(서버 렌더 · 하이드레이션 전) · frame 이 없을 때는 클로즈업과 포인트를 세로로 놓는 기본 화면이다.
 */
export function CraftZoom({
  frame,
  points,
  closeups,
  intro,
}: {
  frame: HomeMediaSlot | null;
  points: DetailPoint[];
  closeups: HomeMediaSlot[];
  /** 전신 단계의 캡션(섹션 본문) */
  intro: string;
}) {
  const hydrated = useHydrated();
  const t = useDictionary().home;
  const wrapperRef = useRef<HTMLDivElement>(null);
  const [step, setStep] = useState(0);
  const zoomable = frame !== null && points.some((point) => point.focus);
  const steps = points.length + 1;

  useEffect(() => {
    const wrapper = wrapperRef.current;
    // 하이드레이션 전에는 기본 화면이라 무대(wrapper)가 없다 — hydrated 가 바뀌면 다시 붙인다
    if (!hydrated || !wrapper || !zoomable) return;
    let frameId = 0;
    const update = () => {
      frameId = 0;
      const rect = wrapper.getBoundingClientRect();
      const travel = rect.height - window.innerHeight;
      setStep(craftStep(travel > 0 ? -rect.top / travel : 0, steps));
    };
    const onScroll = () => {
      if (!frameId) frameId = requestAnimationFrame(update);
    };
    update();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    return () => {
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
      cancelAnimationFrame(frameId);
    };
  }, [hydrated, steps, zoomable]);

  if (!hydrated || !zoomable || !frame) {
    return (
      <div className="mx-auto mt-8 flex max-w-3xl flex-col gap-12 px-4">
        {intro && <p className="text-center leading-7 whitespace-pre-line">{intro}</p>}
        {/* 메인 전시 API 의 디테일은 포인트 = 사진 캡션이라 제목 · 설명이 빌 수 있다 — 빈 줄은 그리지 않는다 */}
        {points.map((point, index) => (
          <figure key={index} className="flex flex-col gap-4">
            {closeups[index] && <MediaFrame slot={closeups[index]} className="aspect-[3/4]" />}
            {(point.title || point.text) && (
              <figcaption className="flex flex-col gap-2">
                {point.title && <span className="font-serif text-lg">{point.title}</span>}
                {point.text && <span className="leading-7">{point.text}</span>}
              </figcaption>
            )}
          </figure>
        ))}
      </div>
    );
  }

  const active = step === 0 ? null : points[step - 1];
  const focus = active?.focus ?? null;
  // 확대 기준점을 그 부분으로 두고 배율만 바꾼다 — 그 부분이 프레임 가운데 쪽으로 커진다
  const transform = focus
    ? {
        transformOrigin: `${focus.x * 100}% ${focus.y * 100}%`,
        transform: `scale(${focus.scale})`,
      }
    : { transformOrigin: '50% 50%', transform: 'scale(1)' };

  return (
    <div ref={wrapperRef} className="relative mt-10" style={{ height: `${steps * 75}svh` }}>
      <div className="sticky top-[var(--site-header-h)] flex h-[calc(100svh-var(--site-header-h))] flex-col items-center justify-center gap-6 px-4 lg:flex-row lg:gap-12">
        <div className="relative aspect-[4/5] w-[70vw] overflow-hidden lg:w-[30vw]">
          <div
            className="absolute inset-0 transition-transform duration-700 ease-out motion-reduce:transition-none"
            style={transform}
          >
            <MediaFrame slot={frame} className="size-full" />
          </div>
          {/* 사진 위 지점 — 전신 단계에서만 보이고, 누르면 그 포인트로 간다 */}
          {points.map((point, index) =>
            point.focus ? (
              <button
                key={point.title}
                type="button"
                aria-label={point.title}
                aria-pressed={step === index + 1}
                onClick={() => setStep(index + 1)}
                style={{ left: `${point.focus.x * 100}%`, top: `${point.focus.y * 100}%` }}
                className={cx(
                  'absolute size-11 -translate-x-1/2 -translate-y-1/2 transition-opacity',
                  'after:absolute after:inset-[15px] after:rounded-full after:bg-bg after:ring-1 after:ring-fg',
                  step === 0 ? 'opacity-100' : 'pointer-events-none opacity-0',
                )}
              />
            ) : null,
          )}
        </div>

        <div
          aria-live="polite"
          className="flex max-w-[28ch] flex-col gap-2 text-center lg:text-left"
        >
          {active ? (
            <>
              <p className="font-serif text-lg">{active.title}</p>
              <p className="leading-7">{active.text}</p>
            </>
          ) : (
            <p className="leading-7">{intro}</p>
          )}
          {step > 0 && (
            <button
              type="button"
              onClick={() => setStep(0)}
              className="mt-2 self-center text-xs tracking-[0.15em] underline underline-offset-4 lg:self-start"
            >
              {t.craftOverview}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
