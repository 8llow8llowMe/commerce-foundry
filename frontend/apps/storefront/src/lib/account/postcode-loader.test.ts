import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

type FakeScript = { src: string; async: boolean; onload?: () => void; onerror?: () => void };

// node 환경이라 window · document 를 흉내 낸다. 모듈 상태(불러오는 중)를 테스트마다 새로 시작한다
describe('openPostcode — 스크립트 불러오기', () => {
  let appended: FakeScript[];
  const win: { daum?: { Postcode?: unknown } } = {};

  beforeEach(() => {
    vi.resetModules();
    appended = [];
    delete win.daum;
    vi.stubGlobal('window', win);
    vi.stubGlobal('document', {
      createElement: () => ({ src: '', async: false }),
      head: { append: (script: FakeScript) => appended.push(script) },
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const fakePostcode = () => {
    const open = vi.fn();
    const Postcode = vi.fn(function (this: { open: () => void }) {
      this.open = open;
    });
    return { Postcode, open };
  };

  it('불러오지 못하면 reject 하고, 다음 클릭에서 다시 불러온다', async () => {
    const { openPostcode } = await import('./postcode');

    const first = openPostcode(() => {});
    expect(appended).toHaveLength(1);
    appended[0]!.onerror?.();
    await expect(first).rejects.toThrow();

    const { Postcode, open } = fakePostcode();
    const second = openPostcode(() => {});
    expect(appended).toHaveLength(2);
    win.daum = { Postcode };
    appended[1]!.onload?.();
    await second;
    expect(open).toHaveBeenCalledTimes(1);
  });

  it('미리 불러 뒀으면 스크립트를 다시 넣지 않고 바로 연다', async () => {
    const { openPostcode, preloadPostcode } = await import('./postcode');
    preloadPostcode();
    const { Postcode, open } = fakePostcode();
    win.daum = { Postcode };
    appended[0]!.onload?.();
    await Promise.resolve();

    void openPostcode(() => {});
    expect(open).toHaveBeenCalledTimes(1);
    expect(appended).toHaveLength(1);
  });
});
