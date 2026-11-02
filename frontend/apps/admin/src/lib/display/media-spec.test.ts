import { describe, expect, it } from 'vitest';
import { MEDIA_SPECS, mediaSizeNotices, mediaSpecHint, specFor } from './media-spec';

describe('media-spec', () => {
  it('유형별 안내 — 히어로는 PC · 모바일을 따로, 상품 픽은 없다', () => {
    expect(mediaSpecHint('HERO')).toBe(
      '권장 이미지: PC 세로 4:5 · 1600×2000px 이상, 모바일 세로 4:5 · 1080×1350px 이상. 비율이 다르면 가장자리가 잘립니다.',
    );
    // 스토리는 레이아웃(브랜드 설정)에 따라 칸이 달라 비율을 정하지 않는다
    expect(mediaSpecHint('STORY')).toContain('권장 이미지: 2000×1600px 이상.');
    expect(mediaSpecHint('STORY')).toContain('가운데');
    expect(mediaSpecHint('PRODUCT_PICK')).toBeNull();
  });

  it('칸 이름으로 규격을 찾고, 칸이 하나면 그것', () => {
    expect(specFor('HERO', '모바일')?.minWidth).toBe(1080);
    expect(specFor('LOOK', '모바일')).toBe(MEDIA_SPECS.LOOK[0]);
    expect(specFor('PRODUCT_PICK', 'PC')).toBeNull();
  });

  it('작거나 비율이 다르면 알리고, 맞으면 빈 목록', () => {
    const pc = MEDIA_SPECS.HERO[0]!;
    expect(mediaSizeNotices(pc, 1600, 2000)).toEqual([]);
    expect(mediaSizeNotices(pc, 3200, 4000)).toEqual([]);
    expect(mediaSizeNotices(pc, 800, 1000)).toEqual([
      '800×1000px 로 권장(1600×2000px)보다 작아 큰 화면에서 흐려 보일 수 있습니다.',
    ]);
    expect(mediaSizeNotices(pc, 2000, 2000)).toEqual([
      '비율이 권장(4:5)과 달라 메인에서 가장자리가 잘립니다.',
    ]);
    // 3:4 는 4:5 와 6% 남짓 달라 잘린다고 알린다
    expect(mediaSizeNotices(pc, 1800, 2400)).toHaveLength(1);
    expect(mediaSizeNotices(pc, 0, 0)).toEqual([]);
    // 스토리는 해상도만 본다 — 가로 사진도 비율 안내가 없다
    expect(mediaSizeNotices(MEDIA_SPECS.STORY[0]!, 2400, 1600)).toEqual([]);
    expect(mediaSizeNotices(MEDIA_SPECS.STORY[0]!, 1200, 1600)).toHaveLength(1);
  });
});
