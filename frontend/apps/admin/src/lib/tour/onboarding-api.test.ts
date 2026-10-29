import { describe, expect, it } from 'vitest';
import { ALL_TOURS } from './tours';
import { guideOf, isFinished, isTourStatus } from './onboarding-api';

describe('guideOf', () => {
  it('투어 아이디의 -v 꼬리를 떼 서버 안내 키 · 버전으로 바꾼다', () => {
    expect(guideOf('console-v1')).toEqual({ key: 'console', version: 1 });
    expect(guideOf('size-chart-editor-v12')).toEqual({ key: 'size-chart-editor', version: 12 });
  });

  it('꼬리가 없거나 서버 키 형식이 아니면 서버에 기록하지 않는다', () => {
    expect(guideOf('console')).toBeNull();
    expect(guideOf('console-v0')).toBeNull();
    expect(guideOf('Console-v1')).toBeNull();
    expect(guideOf('1console-v1')).toBeNull();
    expect(guideOf(`${'a'.repeat(65)}-v1`)).toBeNull();
  });

  it('모든 투어가 서버 안내 키로 바뀐다(서버 허용 목록 operations.onboarding.guides 와 맞춘다)', () => {
    expect(ALL_TOURS.map((tour) => guideOf(tour.id))).toEqual([
      { key: 'console', version: 1 },
      { key: 'stocks', version: 1 },
      { key: 'stock-detail', version: 1 },
      { key: 'size-charts', version: 1 },
      { key: 'size-chart-editor', version: 1 },
      { key: 'size-chart-templates', version: 1 },
    ]);
  });
});

describe('isTourStatus · isFinished', () => {
  it('네 상태만 받고, 완료 · 건너뜀이 끝난 상태다', () => {
    expect(isTourStatus('SKIPPED')).toBe(true);
    expect(isTourStatus('DONE')).toBe(false);
    expect(isTourStatus(null)).toBe(false);
    expect(isFinished('COMPLETED')).toBe(true);
    expect(isFinished('SKIPPED')).toBe(true);
    expect(isFinished('IN_PROGRESS')).toBe(false);
  });
});
