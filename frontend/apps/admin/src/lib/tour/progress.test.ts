import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import type { OnboardingApi, OnboardingMe, TourStatus, TourUpdate } from './onboarding-api';
import {
  createTourProgressStore,
  hasNewerGuide,
  isTourDone,
  MAX_RETRIES,
  nextTour,
  TOUR_PROGRESS_KEY,
  type ServerProgress,
  type TourState,
} from './progress';

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => void data.set(key, value),
    removeItem: (key: string) => void data.delete(key),
    data,
  };
}

const tour = (guideKey: string, code: TourStatus, guideVersion = 1) => ({
  guideKey,
  guideVersion,
  status: { code },
  currentStep: null,
  finishedAt: null,
});

/** 응답을 차례로 꺼내 쓰는 가짜 API. 함수면 부를 때 결과를 정한다 */
function fakeApi(gets: (OnboardingMe | Error)[] = [], puts: (Error | null)[] = []) {
  const sent: { key: string; body: TourUpdate }[] = [];
  let gotten = 0;
  const api: OnboardingApi = {
    getMine: async () => {
      gotten += 1;
      const next = gets.shift() ?? { tours: [] };
      if (next instanceof Error) throw next;
      return next;
    },
    updateTour: async (key, body) => {
      sent.push({ key, body });
      const next = puts.shift() ?? null;
      if (next) throw next;
      return null;
    },
  };
  return { api, sent, gets: () => gotten };
}

function manualScheduler() {
  const runs: (() => void)[] = [];
  return {
    schedule: (run: () => void) => {
      runs.push(run);
      return () => runs.splice(runs.indexOf(run), 1);
    },
    runs,
  };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));
const ready = (tours: [string, TourStatus, number?][]): ServerProgress => ({
  mode: 'ready',
  tours: new Map(tours.map(([key, status, version = 1]) => [key, { status, version }])),
});
const state = (server: ServerProgress, seen: string[] = []): TourState => ({
  seen: new Set(seen),
  requested: null,
  requestSeq: 0,
  server,
});

const conflict = (code: string) => new ApiError('conflict', 409, code, null);

describe('createTourProgressStore — 이 브라우저 기록(서버 API 가 없을 때)', () => {
  const local = async (storage = memoryStorage()) => {
    const store = createTourProgressStore(
      () => storage,
      fakeApi([new ApiError('notFound', 404, null, null)]).api,
    );
    await store.refresh();
    return store;
  };

  it('서버에 온보딩 API 가 없으면(404) 예전처럼 이 브라우저 기록으로 정한다', async () => {
    const store = await local(memoryStorage({ [TOUR_PROGRESS_KEY]: '["console-v1"]' }));
    expect(store.get().server).toEqual({ mode: 'local' });
    expect(isTourDone('console-v1', store.get())).toBe(true);
    expect(isTourDone('stocks-v1', store.get())).toBe(false);
  });

  it('저장소의 본 투어를 읽고, 이상한 값은 버린다', () => {
    const storage = memoryStorage({
      [TOUR_PROGRESS_KEY]: JSON.stringify(['a', 3, 'x'.repeat(65)]),
    });
    expect([...createTourProgressStore(() => storage).get().seen]).toEqual(['a']);
    expect(
      createTourProgressStore(() => memoryStorage({ [TOUR_PROGRESS_KEY]: '{' })).get().seen.size,
    ).toBe(0);
  });

  it('끝내면 본 것으로 저장하고 요청을 지운다. 바뀌지 않으면 같은 스냅숏이다', async () => {
    const storage = memoryStorage();
    const store = await local(storage);
    let calls = 0;
    store.subscribe(() => (calls += 1));
    store.request('a');
    expect(store.get()).toMatchObject({ requested: 'a', requestSeq: 1 });
    store.finish('a');
    expect(store.get()).toMatchObject({ seen: new Set(['a']), requested: null, requestSeq: 1 });
    expect(JSON.parse(storage.data.get(TOUR_PROGRESS_KEY)!)).toEqual(['a']);
    const snapshot = store.get();
    store.finish('a');
    store.clearRequest();
    expect(store.get()).toBe(snapshot);
    expect(calls).toBe(2);
  });

  it('요청만 지울 때는 저장소에 쓰지 않는다', async () => {
    const storage = memoryStorage({ [TOUR_PROGRESS_KEY]: '["b"]' });
    const store = await local(storage);
    store.request('a');
    storage.data.set(TOUR_PROGRESS_KEY, '["b","x"]');
    store.clearRequest();
    expect(store.get().requested).toBeNull();
    expect(storage.data.get(TOUR_PROGRESS_KEY)).toBe('["b","x"]');
  });

  it('다른 탭이 남긴 기록을 덮어쓰지 않고 합친다', async () => {
    const storage = memoryStorage();
    const store = await local(storage);
    storage.data.set(TOUR_PROGRESS_KEY, '["other"]');
    store.finish('a');
    expect(JSON.parse(storage.data.get(TOUR_PROGRESS_KEY)!).sort()).toEqual(['a', 'other']);
  });

  it('다시 보기 요청은 본 표시를 지운다. 같은 투어를 또 요청하면 요청 번호가 오른다. 초기화는 모두 지운다', async () => {
    const store = await local(memoryStorage({ [TOUR_PROGRESS_KEY]: '["a","b"]' }));
    store.request('a');
    store.request('a');
    expect([...store.get().seen]).toEqual(['b']);
    expect(store.get().requestSeq).toBe(2);
    store.reset();
    expect(store.get()).toMatchObject({ seen: new Set(), requested: null, requestSeq: 2 });
  });

  it('저장소를 못 쓰면 메모리에만 기억한다', async () => {
    const blocked = () => {
      throw new Error('blocked');
    };
    const store = createTourProgressStore(
      () => ({ getItem: blocked, setItem: blocked, removeItem: blocked }),
      fakeApi([new ApiError('notFound', 404, null, null)]).api,
    );
    await store.refresh();
    store.finish('a');
    expect(store.get().seen.has('a')).toBe(true);
    store.signOut();
    expect(store.get().seen.size).toBe(0);
  });
});

describe('createTourProgressStore — 운영자별 서버 기록', () => {
  it('불러오기 전 · 불러오지 못하면 끝냈는지 모른다 — 자동 시작하지 않는다', async () => {
    const store = createTourProgressStore(
      () => memoryStorage(),
      fakeApi([new ApiError('unavailable', 503, null, null)]).api,
    );
    expect(store.get().server.mode).toBe('loading');
    expect(nextTour([{ id: 'console-v1' }], store.get())).toBeNull();
    await store.refresh();
    expect(store.get().server.mode).toBe('unavailable');
    expect(nextTour([{ id: 'console-v1' }], store.get())).toBeNull();
    // 다시 보기는 된다
    store.request('console-v1');
    expect(nextTour([{ id: 'console-v1' }], store.get())).toEqual({ id: 'console-v1' });
  });

  it('서버 기록이 정본이다 — 이 브라우저의 로컬 기록은 서버가 아는 투어에 쓰지 않고, 서버로 올리지도 않는다', async () => {
    const storage = memoryStorage({ [TOUR_PROGRESS_KEY]: '["console-v1","stocks-v1","old-v1"]' });
    const { api, sent } = fakeApi([
      { tours: [tour('console', 'NOT_STARTED'), tour('stocks', 'COMPLETED')] },
    ]);
    const store = createTourProgressStore(() => storage, api);
    await store.refresh();
    expect(isTourDone('console-v1', store.get())).toBe(false);
    expect(isTourDone('stocks-v1', store.get())).toBe(true);
    // 서버가 모르는 투어만 힌트로 정한다
    expect(isTourDone('old-v1', store.get())).toBe(true);
    expect(isTourDone('new-v1', store.get())).toBe(false);
    await settle();
    expect(sent).toEqual([]);
  });

  it('시작하면 진행 중, 끝내면 완료 · 건너뜀을 보낸다. 화면에는 먼저 반영한다', async () => {
    const { api, sent } = fakeApi([
      { tours: [tour('console', 'NOT_STARTED'), tour('stocks', 'NOT_STARTED')] },
    ]);
    const store = createTourProgressStore(() => memoryStorage(), api);
    await store.refresh();
    const tours = [{ id: 'console-v1' }, { id: 'stocks-v1' }];
    expect(nextTour(tours, store.get())?.id).toBe('console-v1');
    store.start('console-v1');
    await settle();
    store.finish('console-v1', 'COMPLETED', 3);
    expect(nextTour(tours, store.get())?.id).toBe('stocks-v1');
    store.start('stocks-v1');
    await settle();
    store.finish('stocks-v1', 'SKIPPED', 0);
    expect(nextTour(tours, store.get())).toBeNull();
    await settle();
    expect(sent).toEqual([
      { key: 'console', body: { guideVersion: 1, status: 'IN_PROGRESS', currentStep: 0 } },
      { key: 'console', body: { guideVersion: 1, status: 'COMPLETED', currentStep: 3 } },
      { key: 'stocks', body: { guideVersion: 1, status: 'IN_PROGRESS', currentStep: 0 } },
      { key: 'stocks', body: { guideVersion: 1, status: 'SKIPPED', currentStep: 0 } },
    ]);
  });

  it('다시 보기는 재실행(NOT_STARTED)을 먼저 보내고 진행을 기록한다', async () => {
    const { api, sent } = fakeApi([{ tours: [tour('console', 'COMPLETED')] }]);
    const store = createTourProgressStore(() => memoryStorage(), api);
    await store.refresh();
    store.request('console-v1');
    expect(nextTour([{ id: 'console-v1' }], store.get())?.id).toBe('console-v1');
    store.start('console-v1');
    await settle();
    store.finish('console-v1', 'SKIPPED', 1);
    await settle();
    expect(sent.map(({ body }) => body.status)).toEqual(['NOT_STARTED', 'IN_PROGRESS', 'SKIPPED']);
    expect(isTourDone('console-v1', store.get())).toBe(true);
  });

  it('아직 못 보낸 진행 기록은 나중 것만 보낸다. 재실행 앞의 기록은 버린다', async () => {
    const { api, sent } = fakeApi([{ tours: [tour('console', 'NOT_STARTED')] }]);
    const store = createTourProgressStore(() => memoryStorage(), api);
    await store.refresh();
    store.start('console-v1'); // 바로 보내기 시작한다
    store.finish('console-v1', 'SKIPPED', 0); // 기다린다
    store.request('console-v1'); // 앞의 건너뜀을 버린다
    store.start('console-v1');
    store.finish('console-v1', 'COMPLETED', 2); // 진행 중을 대신한다
    await settle();
    expect(sent.map(({ body }) => body.status)).toEqual([
      'IN_PROGRESS',
      'NOT_STARTED',
      'COMPLETED',
    ]);
  });

  it('전체 초기화는 서버가 아는 투어마다 재실행을 보낸다', async () => {
    const { api, sent } = fakeApi([
      { tours: [tour('console', 'COMPLETED'), tour('stocks', 'SKIPPED', 2)] },
    ]);
    const store = createTourProgressStore(() => memoryStorage(), api);
    await store.refresh();
    store.reset();
    expect(isTourDone('console-v1', store.get())).toBe(false);
    await settle();
    expect(sent).toEqual([
      { key: 'console', body: { guideVersion: 1, status: 'NOT_STARTED', currentStep: null } },
      { key: 'stocks', body: { guideVersion: 2, status: 'NOT_STARTED', currentStep: null } },
    ]);
  });

  it('보내지 못하면(409 ONBOARDING_021 · 5xx) 화면 기록은 두고 나중에 다시 보낸다. 연달아 실패하면 그만둔다', async () => {
    const failures = Array.from({ length: MAX_RETRIES + 1 }, () => conflict('ONBOARDING_021'));
    const { api, sent } = fakeApi(
      [{ tours: [tour('console', 'NOT_STARTED')] }],
      [new ApiError('server', 500, null, null)],
    );
    const timer = manualScheduler();
    const store = createTourProgressStore(() => memoryStorage(), api, timer.schedule);
    await store.refresh();
    store.finish('console-v1', 'COMPLETED', 3);
    await settle();
    expect(isTourDone('console-v1', store.get())).toBe(true);
    expect(timer.runs).toHaveLength(1);
    timer.runs.shift()!();
    await settle();
    expect(sent).toHaveLength(2);
    expect(sent[1]!.body.status).toBe('COMPLETED');
    expect(timer.runs).toHaveLength(0);

    // 계속 실패하면 MAX_RETRIES 번까지만 다시 보낸다
    const second = fakeApi([{ tours: [tour('console', 'NOT_STARTED')] }], [...failures]);
    const retry = manualScheduler();
    const other = createTourProgressStore(() => memoryStorage(), second.api, retry.schedule);
    await other.refresh();
    other.finish('console-v1', 'COMPLETED', 3);
    for (let i = 0; i < MAX_RETRIES + 1; i += 1) {
      await settle();
      retry.runs.shift()?.();
    }
    await settle();
    expect(second.sent).toHaveLength(MAX_RETRIES + 1);
    expect(retry.runs).toHaveLength(0);
  });

  it('허용 목록에 없는 투어(404 ONBOARDING_001)는 그 투어만 이 브라우저 기록으로 간다', async () => {
    const { api, sent } = fakeApi(
      [{ tours: [tour('console', 'NOT_STARTED'), tour('stocks', 'NOT_STARTED')] }],
      [new ApiError('notFound', 404, 'ONBOARDING_001', null)],
    );
    const store = createTourProgressStore(() => memoryStorage(), api);
    await store.refresh();
    store.finish('console-v1', 'COMPLETED', 0);
    await settle();
    expect(store.get().server.mode).toBe('ready');
    expect(isTourDone('console-v1', store.get())).toBe(true);
    store.finish('console-v1', 'COMPLETED', 0);
    store.finish('stocks-v1', 'SKIPPED', 0);
    await settle();
    expect(sent.map(({ key }) => key)).toEqual(['console', 'stocks']);
  });

  it('옛 번들(409 ONBOARDING_020)이면 GET 을 한 번만 다시 부르고, 새 버전이면 새로 고침 대상이다', async () => {
    const { api, gets } = fakeApi(
      [
        { tours: [tour('console', 'NOT_STARTED'), tour('stocks', 'NOT_STARTED')] },
        { tours: [tour('console', 'NOT_STARTED', 2), tour('stocks', 'COMPLETED', 2)] },
      ],
      [conflict('ONBOARDING_020'), conflict('ONBOARDING_020')],
    );
    const store = createTourProgressStore(() => memoryStorage(), api);
    await store.refresh();
    store.finish('console-v1', 'SKIPPED', 0);
    await settle();
    await settle();
    expect(gets()).toBe(2);
    expect(hasNewerGuide(['console-v1', 'stocks-v1'], store.get())).toBe(true);
    // 버전이 다른 투어는 이 세션 동안 이 브라우저 기록으로 정한다
    expect(isTourDone('console-v1', store.get())).toBe(true);
    expect(isTourDone('stocks-v1', store.get())).toBe(false);
    store.finish('stocks-v1', 'SKIPPED', 0);
    await settle();
    expect(gets()).toBe(2);
  });

  it('로그아웃하면 기록 · 보내지 못한 기록 · 이 브라우저 힌트를 지우고, 다음 운영자는 자기 기록으로 시작한다', async () => {
    const storage = memoryStorage({ [TOUR_PROGRESS_KEY]: '["console-v1"]' });
    const { api, sent } = fakeApi(
      [{ tours: [tour('console', 'COMPLETED')] }, { tours: [tour('console', 'NOT_STARTED')] }],
      [new ApiError('server', 500, null, null)],
    );
    const timer = manualScheduler();
    const store = createTourProgressStore(() => storage, api, timer.schedule);
    await store.refresh();
    store.request('console-v1');
    await settle();
    expect(timer.runs).toHaveLength(1);

    store.signOut();
    expect(store.get().server.mode).toBe('loading');
    expect(store.get().seen.size).toBe(0);
    expect(storage.data.has(TOUR_PROGRESS_KEY)).toBe(false);
    expect(timer.runs).toHaveLength(0);

    await store.refresh();
    expect(nextTour([{ id: 'console-v1' }], store.get())?.id).toBe('console-v1');
    await settle();
    // 이전 운영자가 못 보낸 재실행은 다음 운영자 계정으로 가지 않는다
    expect(sent).toHaveLength(1);
  });

  it('불러오는 중에 운영자가 바뀌면 늦게 온 이전 응답을 버린다', async () => {
    let resolveFirst: (me: OnboardingMe) => void = () => undefined;
    const api: OnboardingApi = {
      getMine: () => new Promise((resolve) => (resolveFirst = resolve)),
      updateTour: async () => null,
    };
    const store = createTourProgressStore(() => memoryStorage(), api);
    const first = store.refresh();
    store.signOut();
    resolveFirst({ tours: [tour('console', 'COMPLETED')] });
    await first;
    expect(store.get().server.mode).toBe('loading');
  });
});

describe('nextTour · isTourDone · hasNewerGuide', () => {
  const A = { id: 'a' };
  const B = { id: 'b' };

  it('끝내지 않은 첫 투어, 요청이 이 화면 투어면 그것, 다른 화면 요청은 무시한다', () => {
    const local: ServerProgress = { mode: 'local' };
    expect(nextTour([A, B], state(local))).toBe(A);
    expect(nextTour([A, B], state(local, ['a']))).toBe(B);
    expect(nextTour([A, B], state(local, ['a', 'b']))).toBeNull();
    expect(nextTour([A, B], { ...state(local, ['b']), requested: 'b' })).toBe(B);
    expect(nextTour([A, B], { ...state(local, ['a', 'b']), requested: 'z' })).toBeNull();
  });

  it('진행 중은 끝내지 않은 것이다. 서버 버전이 다르면 이 브라우저 기록으로 정한다', () => {
    const server = ready([
      ['console', 'IN_PROGRESS'],
      ['stocks', 'SKIPPED', 2],
    ]);
    expect(isTourDone('console-v1', state(server, ['console-v1']))).toBe(false);
    expect(isTourDone('stocks-v1', state(server))).toBe(false);
    expect(isTourDone('stocks-v1', state(server, ['stocks-v1']))).toBe(true);
    expect(isTourDone('stocks-v2', state(server))).toBe(true);
    expect(hasNewerGuide(['console-v1', 'stocks-v1'], state(server))).toBe(true);
    expect(hasNewerGuide(['console-v1', 'stocks-v2'], state(server))).toBe(false);
    expect(hasNewerGuide(['stocks-v1'], state({ mode: 'local' }))).toBe(false);
  });
});
