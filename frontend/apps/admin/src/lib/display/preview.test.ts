import { ApiError } from '@cf/api-client';
import { describe, expect, it, vi } from 'vitest';
import { PREVIEW_CHECK_INPUT, PREVIEW_FAILED, type PreviewFailure } from './errors';
import { createForm, draftRequest, editForm } from './form';
import {
  createPreviewLoader,
  createPreviewSession,
  PREVIEW_DISPLAY,
  type PreviewOutcome,
  isPreviewReady,
  NEW_SECTION_ID,
  parseOrigin,
  PREVIEW_READY,
  previewRequest,
  previewScale,
  previewSections,
} from './preview';
import { mediaItem, productItem, section } from './test-fixtures';

const hero = section('HERO', {
  sectionId: '1',
  title: '저장된 히어로',
  items: [mediaItem()],
});
const pick = section('PRODUCT_PICK', {
  sectionId: '2',
  title: '저장된 상품 픽',
  items: [productItem('11')],
});

describe('draftRequest', () => {
  it('저장과 달리 칸 오류가 있어도 지금 값을 싣는다', () => {
    const form = {
      ...editForm(pick, 'PRODUCT_PICK'),
      title: '  고친 제목  ',
      linkUrl: 'javascript:alert(1)',
      startAt: '잘못된 값',
      products: [],
    };
    expect(draftRequest(form)).toMatchObject({
      sectionType: 'PRODUCT_PICK',
      title: '고친 제목',
      // 링크 · 문구는 스토어프론트가 safeHref · 텍스트 노드로만 그린다
      linkUrl: 'javascript:alert(1)',
      startAt: null,
      items: [],
    });
  });

  it('미디어는 고친 대체 텍스트로, 고치지 않은 기간은 불러온 값 그대로 싣는다', () => {
    const loaded = section('HERO', {
      startAt: '2026-10-01T00:00:00.123456Z',
      items: [mediaItem({ altText: '처음 문구' })],
    });
    const form = editForm(loaded, 'HERO');
    form.media[0] = { ...form.media[0]!, altText: '고친 문구' };
    const request = draftRequest(form);
    expect(request.startAt).toBe('2026-10-01T00:00:00.123456Z');
    expect(request.items[0]).toMatchObject({ itemType: 'MEDIA', media: { altText: '고친 문구' } });
  });
});

describe('previewSections', () => {
  it('저장된 섹션은 화면 순서 그대로 받은 값을 싣는다', () => {
    const sections = previewSections([pick, hero], null);
    expect(sections.map((item) => item.sectionId)).toEqual(['2', '1']);
    expect(sections[0]).toMatchObject({
      title: '저장된 상품 픽',
      items: [{ itemType: 'PRODUCT', productId: '11' }],
    });
  });

  it('고치는 섹션은 그 자리에 저장 전 값을 싣는다', () => {
    const form = { ...editForm(hero, 'HERO'), title: '저장 전 제목' };
    const sections = previewSections([hero, pick], { mode: 'edit', sectionId: '1', form });
    expect(sections[0]).toMatchObject({ sectionId: '1', title: '저장 전 제목' });
    expect(sections[1]).toMatchObject({ sectionId: '2', title: '저장된 상품 픽' });
  });

  it('새 섹션은 임시 아이디로 맨 뒤에 둔다', () => {
    const form = { ...createForm('STORY'), title: '새 스토리', visible: true };
    const sections = previewSections([hero], { mode: 'create', form });
    expect(sections).toHaveLength(2);
    expect(sections[1]).toMatchObject({
      sectionId: NEW_SECTION_ID,
      title: '새 스토리',
      visible: true,
    });
  });
});

describe('parseOrigin', () => {
  it('http(s) 주소의 origin 만 받는다', () => {
    expect(parseOrigin('https://shop.example.test/path')).toBe('https://shop.example.test');
    expect(parseOrigin('http://localhost:3000')).toBe('http://localhost:3000');
    expect(parseOrigin('javascript:alert(1)')).toBeNull();
    expect(parseOrigin('not a url')).toBeNull();
    expect(parseOrigin('')).toBeNull();
    expect(parseOrigin(undefined)).toBeNull();
  });
});

describe('previewScale', () => {
  it('칸이 좁으면 줄이고 넓으면 그대로 둔다', () => {
    expect(previewScale(1440, 720)).toBe(0.5);
    expect(previewScale(390, 600)).toBe(1);
    expect(previewScale(1440, 0)).toBe(1);
  });
});

describe('isPreviewReady', () => {
  const frame = {} as MessageEventSource;
  const storefront = 'https://shop.example.test';
  const event = (overrides: Partial<Pick<MessageEvent, 'origin' | 'source' | 'data'>> = {}) => ({
    origin: storefront,
    source: frame,
    data: { type: PREVIEW_READY },
    ...overrides,
  });

  it('스토어프론트 origin 의 그 iframe 이 보낸 준비 알림만 믿는다', () => {
    expect(isPreviewReady(event(), storefront, frame)).toBe(true);
    expect(isPreviewReady(event({ origin: 'https://evil.example.test' }), storefront, frame)).toBe(
      false,
    );
    expect(isPreviewReady(event({ source: {} as MessageEventSource }), storefront, frame)).toBe(
      false,
    );
    expect(isPreviewReady(event(), storefront, null)).toBe(false);
    expect(isPreviewReady(event({ data: 'ready' }), storefront, frame)).toBe(false);
  });
});

describe('previewRequest', () => {
  it('화면 아이디를 빼고 화면 순서 그대로 보낸다', () => {
    const body = previewRequest(previewSections([pick, hero], null));
    expect(body.sections.map((item) => item.title)).toEqual(['저장된 상품 픽', '저장된 히어로']);
    expect(body.sections.every((item) => !('sectionId' in item))).toBe(true);
  });
});

/** 밖에서 끝낼 수 있는 응답 — 응답 순서를 테스트가 정한다 */
function deferred() {
  let resolve!: (value: unknown) => void;
  let reject!: (cause: unknown) => void;
  const promise = new Promise<unknown>((ok, fail) => {
    resolve = ok;
    reject = fail;
  });
  return { promise, resolve, reject };
}

describe('createPreviewLoader', () => {
  const sections = previewSections([hero, pick], null);
  const response = (title: string) => ({ sections: [{ sectionId: 'preview-section-0', title }] });

  it('늦게 온 앞 응답은 버리고 마지막 요청의 결과만 돌려준다', async () => {
    const calls = [deferred(), deferred()];
    const signals: AbortSignal[] = [];
    let n = 0;
    const loader = createPreviewLoader((_body, signal) => {
      signals.push(signal);
      return calls[n++]!.promise;
    });
    const first = loader.load(sections);
    const second = loader.load(sections);
    // 새 요청을 보내면 앞 요청을 취소한다
    expect(signals[0]?.aborted).toBe(true);
    calls[1]!.resolve(response('새 응답'));
    calls[0]!.resolve(response('앞 응답'));
    expect(await second).toEqual({ kind: 'ok', display: response('새 응답') });
    expect(await first).toBeNull();
  });

  it('앞 요청의 실패도 늦게 오면 버린다', async () => {
    const calls = [deferred(), deferred()];
    let n = 0;
    const loader = createPreviewLoader(() => calls[n++]!.promise);
    const first = loader.load(sections);
    const second = loader.load(sections);
    calls[1]!.resolve(response('새 응답'));
    calls[0]!.reject(new ApiError('validation', 400, 'PRODUCT_140', null, []));
    expect(await first).toBeNull();
    expect((await second)?.kind).toBe('ok');
  });

  it('본문은 화면 아이디 없이 보낸다', async () => {
    const fetchPreview = vi.fn(async () => response('응답'));
    await createPreviewLoader(fetchPreview).load(sections);
    expect(fetchPreview).toHaveBeenCalledWith(previewRequest(sections), expect.any(AbortSignal));
  });

  it('실패는 패널 안내로, 모양이 다른 응답은 다시 시도로 돌려준다', async () => {
    const failing = createPreviewLoader(async () => {
      throw new ApiError('validation', 400, 'PRODUCT_140', null, [
        { field: 'sections[1].sectionType', message: '', code: 'PRODUCT_140' },
      ]);
    });
    expect(await failing.load(sections)).toMatchObject({
      kind: 'failed',
      failure: { message: PREVIEW_CHECK_INPUT },
    });
    const odd = createPreviewLoader(async () => ({ items: [] }));
    expect(await odd.load(sections)).toMatchObject({
      kind: 'failed',
      failure: { message: PREVIEW_FAILED, retry: true },
    });
  });

  it('닫으면 남은 응답을 버린다', async () => {
    const call = deferred();
    const loader = createPreviewLoader(() => call.promise);
    const pending = loader.load(sections);
    loader.cancel();
    call.resolve(response('응답'));
    expect(await pending).toBeNull();
  });
});

describe('createPreviewSession', () => {
  const sections = previewSections([hero, pick], null);
  const display = (title: string) => ({ sections: [{ sectionId: 'preview-section-0', title }] });
  const failed = (failure: Partial<PreviewFailure> = {}): PreviewOutcome => ({
    kind: 'failed',
    failure: {
      message: PREVIEW_CHECK_INPUT,
      details: [],
      retry: false,
      cooldownSec: null,
      ...failure,
    },
  });

  const setup = (outcomes: (PreviewOutcome | null)[], now = () => 0) => {
    const load = vi.fn(async () => outcomes.shift() ?? null);
    const cancel = vi.fn();
    const post = vi.fn();
    const onFailure = vi.fn();
    const session = createPreviewSession({ loader: { load, cancel }, post, onFailure, now });
    return { session, load, cancel, post, onFailure };
  };

  it('준비 알림 전에는 보내지 않고, 알림이 오면 마지막 성공 응답을 보낸다', async () => {
    const { session, post } = setup([{ kind: 'ok', display: display('첫 응답') }]);
    session.ready();
    expect(post).not.toHaveBeenCalled();
    session.frameReset();
    await session.update(sections);
    expect(post).not.toHaveBeenCalled();
    session.ready();
    expect(post).toHaveBeenCalledWith({ type: PREVIEW_DISPLAY, display: display('첫 응답') });
  });

  it('성공하면 보내고 안내를 지운다', async () => {
    const { session, post, onFailure } = setup([{ kind: 'ok', display: display('응답') }]);
    session.ready();
    await session.update(sections);
    expect(onFailure).toHaveBeenLastCalledWith(null);
    expect(post).toHaveBeenCalledTimes(1);
  });

  it('400 이면 마지막 성공 응답을 두고 다시 보내지 않으며 안내를 세운다', async () => {
    const { session, post, onFailure } = setup([
      { kind: 'ok', display: display('성공') },
      failed(),
    ]);
    session.ready();
    await session.update(sections);
    await session.update(sections);
    expect(post).toHaveBeenCalledTimes(1);
    expect(onFailure).toHaveBeenLastCalledWith(
      expect.objectContaining({ message: PREVIEW_CHECK_INPUT }),
    );
    // iframe 이 다시 열려도 보내는 것은 마지막 성공 응답이다
    session.frameReset();
    session.ready();
    expect(post).toHaveBeenLastCalledWith({ type: PREVIEW_DISPLAY, display: display('성공') });
  });

  it('다시 시도하면 마지막 편집 상태로 다시 부른다', async () => {
    const { session, load } = setup([
      failed({ retry: true }),
      { kind: 'ok', display: display('x') },
    ]);
    await session.retry();
    expect(load).not.toHaveBeenCalled();
    await session.update(sections);
    await session.retry();
    expect(load).toHaveBeenCalledTimes(2);
    expect(load).toHaveBeenLastCalledWith(sections);
  });

  it('429 면 막아 둔 시간 동안은 다시 부르지 않는다', async () => {
    let clock = 1000;
    const { session, load } = setup(
      [failed({ retry: true, cooldownSec: 5 }), { kind: 'ok', display: display('x') }],
      () => clock,
    );
    await session.update(sections);
    clock += 4000;
    await session.retry();
    expect(load).toHaveBeenCalledTimes(1);
    clock += 1000;
    await session.retry();
    expect(load).toHaveBeenCalledTimes(2);
  });

  it('닫으면 남은 요청을 버린다', () => {
    const { session, cancel } = setup([]);
    session.close();
    expect(cancel).toHaveBeenCalledTimes(1);
  });
});
