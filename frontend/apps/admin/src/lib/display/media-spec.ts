import type { SectionTypeCode } from './types';

/**
 * 메인 전시 이미지 권장 비율 · 최소 해상도(#71). 비율은 스토어프론트 메인이 그리는 칸(`apps/storefront/src/components/home`)과 같다 —
 * 히어로 PC · 모바일 모두 세로 4:5(`HeroSection`), 룩 4:5(`LookSection`), 디테일 클로즈업 3:4(`CraftZoom`).
 * 스토리는 브랜드 설정의 레이아웃 순서로 칸이 정해져(curtain 화면 전체 가로 · panel · asymmetric 3:4 · centered 3:2) 비율을 정하지 않는다 — 해상도만 안내한다.
 * 최소 해상도는 가장 큰 칸(히어로 PC 는 1440px 화면에서 폭 52vw ≈ 750px)을 2배 화면에서 흐리지 않게 잡은 값이다. 서버는 비율 · 해상도를 보지 않는다 — 안내다.
 */
export type MediaSpec = {
  /** 칸이 둘이면 붙이는 이름("PC" · "모바일") */
  slot: string | null;
  /** null 이면 비율을 안내 · 검사하지 않는다 */
  ratio: [number, number] | null;
  minWidth: number;
  minHeight: number;
};

export const MEDIA_SPECS: Record<SectionTypeCode, readonly MediaSpec[]> = {
  HERO: [
    { slot: 'PC', ratio: [4, 5], minWidth: 1600, minHeight: 2000 },
    { slot: '모바일', ratio: [4, 5], minWidth: 1080, minHeight: 1350 },
  ],
  PRODUCT_PICK: [],
  // curtain 은 1440px 화면 전체 폭이라 가로를 넉넉히 잡는다
  STORY: [{ slot: null, ratio: null, minWidth: 2000, minHeight: 1600 }],
  // 룩 섹션은 룩 참조라 미디어 칸이 없다(BE #372) — 화보 규격은 룩 관리 화면이 본다
  LOOK: [],
  DETAIL: [{ slot: null, ratio: [3, 4], minWidth: 1200, minHeight: 1600 }],
};

/** 비율이 이만큼(상대 차) 넘게 다르면 잘린다고 알린다 — 4:5 와 3:4 는 6% 남짓 다르다 */
const RATIO_TOLERANCE = 0.03;

/** "PC 세로 4:5 · 1600×2000px 이상" */
export function specLabel(spec: MediaSpec): string {
  const ratio = spec.ratio ? `세로 ${spec.ratio[0]}:${spec.ratio[1]} · ` : '';
  return `${spec.slot ? `${spec.slot} ` : ''}${ratio}${spec.minWidth}×${spec.minHeight}px 이상`;
}

/** 칸 이름으로 규격을 찾는다 — 칸이 하나인 유형은 그 하나 */
export function specFor(type: SectionTypeCode, slot: 'PC' | '모바일'): MediaSpec | null {
  const specs = MEDIA_SPECS[type];
  return specs.find((spec) => spec.slot === slot) ?? specs[0] ?? null;
}

/** 유형별 안내 한 줄. 미디어가 없는 유형은 null */
export function mediaSpecHint(type: SectionTypeCode): string | null {
  const specs = MEDIA_SPECS[type];
  if (specs.length === 0) return null;
  const extra =
    type === 'STORY'
      ? ' 레이아웃에 따라 세로 칸이나 화면 전체(가로)로 보여 가장자리가 잘릴 수 있으니, 중요한 부분은 가운데에 두세요.'
      : ' 비율이 다르면 가장자리가 잘립니다.';
  return `권장 이미지: ${specs.map(specLabel).join(', ')}.${extra}`;
}

/** 불러온 이미지 크기를 권장과 견준다. 문제없으면 빈 배열 — 저장을 막지 않는 안내다(서버는 보지 않는다) */
export function mediaSizeNotices(spec: MediaSpec, width: number, height: number): string[] {
  if (width <= 0 || height <= 0) return [];
  const notices: string[] = [];
  if (width < spec.minWidth || height < spec.minHeight) {
    notices.push(
      `${width}×${height}px 로 권장(${spec.minWidth}×${spec.minHeight}px)보다 작아 큰 화면에서 흐려 보일 수 있습니다.`,
    );
  }
  if (spec.ratio) {
    const [w, h] = spec.ratio;
    const expected = w / h;
    if (Math.abs(width / height - expected) / expected > RATIO_TOLERANCE) {
      notices.push(`비율이 권장(${w}:${h})과 달라 메인에서 가장자리가 잘립니다.`);
    }
  }
  return notices;
}
