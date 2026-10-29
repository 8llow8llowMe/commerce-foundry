import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import type { OnboardingMe, ServerTaskItem } from '@/lib/tour/onboarding-api';
import {
  createOnboardingTasksStore,
  createStartCardPref,
  START_CARD_PREF_KEY,
} from './tasks-store';

const shipping = (my: string): ServerTaskItem => ({
  taskKey: 'SHIPPING_POLICY_SAVED',
  storeStatus: { code: 'NOT_READY' },
  myStatus: { code: my },
  myCompletedAt: my === 'COMPLETED' ? '2026-10-01T00:00:00Z' : null,
});
const me = (my: string): OnboardingMe => ({ tours: [], tasks: [shipping(my)] });

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (cause: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** 부를 때마다 새 약속을 내고, 테스트가 순서대로 풀어 준다 */
function manualApi() {
  const calls: ReturnType<typeof deferred<OnboardingMe>>[] = [];
  return {
    api: {
      getMine: () => {
        const call = deferred<OnboardingMe>();
        calls.push(call);
        return call.promise;
      },
    },
    calls,
  };
}

describe('createOnboardingTasksStore', () => {
  it('읽는 동안은 loading, 읽으면 서버 업무 상태다', async () => {
    const { api, calls } = manualApi();
    const store = createOnboardingTasksStore(api);
    const loading = store.load();
    expect(store.get()).toEqual({ tasks: null, loading: true, failed: false });
    calls[0]!.resolve(me('NOT_COMPLETED'));
    await loading;
    expect(store.get().loading).toBe(false);
    expect(store.get().tasks?.[0]?.progress).toBe('todo');
  });

  it('조회 실패는 미완료와 다르다 — failed 이고 업무를 미완료로 만들지 않는다', async () => {
    const { api, calls } = manualApi();
    const store = createOnboardingTasksStore(api);
    const first = store.load();
    calls[0]!.reject(new ApiError('server', 503, null, null));
    await first;
    expect(store.get()).toEqual({ tasks: null, loading: false, failed: true });

    // 전에 읽은 값이 있으면 그대로 두고 실패만 알린다
    const second = store.load();
    calls[1]!.resolve(me('COMPLETED'));
    await second;
    const third = store.load();
    calls[2]!.reject(new TypeError('network'));
    await third;
    expect(store.get().failed).toBe(true);
    expect(store.get().tasks?.[0]?.progress).toBe('done');
  });

  it('겹쳐 읽으면 마지막 결과만 반영한다', async () => {
    const { api, calls } = manualApi();
    const store = createOnboardingTasksStore(api);
    const older = store.load();
    const newer = store.load();
    calls[1]!.resolve(me('COMPLETED'));
    await newer;
    calls[0]!.resolve(me('NOT_COMPLETED'));
    await older;
    expect(store.get().tasks?.[0]?.progress).toBe('done');
  });

  it('저장 성공 뒤 invalidate — 보고 있는 화면이 있으면 다시 읽는다', async () => {
    const { api, calls } = manualApi();
    const store = createOnboardingTasksStore(api);
    const unsubscribe = store.subscribe(() => {});
    store.invalidate();
    expect(calls).toHaveLength(1);
    expect(store.get().loading).toBe(true);
    calls[0]!.resolve(me('COMPLETED'));
    await calls[0]!.promise;
    await Promise.resolve();
    expect(store.get().tasks?.[0]?.progress).toBe('done');
    unsubscribe();
  });

  it('보고 있는 화면이 없으면 지난 값을 버린다 — 다음에 열 때 지난 미완료가 보이지 않게', async () => {
    const { api, calls } = manualApi();
    const store = createOnboardingTasksStore(api);
    const loading = store.load();
    calls[0]!.resolve(me('NOT_COMPLETED'));
    await loading;
    store.invalidate();
    expect(calls).toHaveLength(1);
    expect(store.get().tasks).toBeNull();
  });

  it('로그아웃하면 이 운영자의 업무 상태를 버리고, 그 전에 시작한 읽기 결과도 버린다(계정 전환)', async () => {
    const { api, calls } = manualApi();
    const store = createOnboardingTasksStore(api);
    const loading = store.load();
    store.signOut();
    calls[0]!.resolve(me('COMPLETED'));
    await loading;
    expect(store.get()).toEqual({ tasks: null, loading: false, failed: false });
  });
});

describe('createStartCardPref', () => {
  function memory() {
    const data = new Map<string, string>();
    return {
      getItem: (key: string) => data.get(key) ?? null,
      setItem: (key: string, value: string) => void data.set(key, value),
      removeItem: (key: string) => void data.delete(key),
      data,
    };
  }

  it('접기 · 나중에를 기억하고 펼치면 지운다', () => {
    const storage = memory();
    const pref = createStartCardPref(() => storage);
    expect(pref.get()).toBe('open');
    pref.set('hidden');
    expect(pref.get()).toBe('hidden');
    expect(storage.data.get(START_CARD_PREF_KEY)).toBe('hidden');
    expect(createStartCardPref(() => storage).get()).toBe('hidden');
    pref.clear();
    expect(storage.data.has(START_CARD_PREF_KEY)).toBe(false);
  });

  it('저장소를 못 쓰거나 모르는 값이면 펼친 것으로 본다', () => {
    const storage = memory();
    storage.data.set(START_CARD_PREF_KEY, 'weird');
    expect(createStartCardPref(() => storage).get()).toBe('open');
    const broken = createStartCardPref(() => {
      throw new Error('blocked');
    });
    expect(broken.get()).toBe('open');
    broken.set('collapsed');
    expect(broken.get()).toBe('collapsed');
  });
});
