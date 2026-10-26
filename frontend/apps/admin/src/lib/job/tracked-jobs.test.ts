import { describe, expect, it, vi } from 'vitest';
import { createTrackedJobsStore, MAX_TRACKED_JOBS, TRACKED_JOBS_KEY } from './tracked-jobs';

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => void data.set(key, value),
    data,
  };
}

describe('createTrackedJobsStore', () => {
  it('최근 것을 앞에 두고 중복 없이 상한까지 저장한다', () => {
    const storage = memoryStorage();
    const store = createTrackedJobsStore(() => storage);
    const listener = vi.fn();
    store.subscribe(listener);

    for (const id of ['1', '2', '3', '4', '5', '6']) store.track(id);
    store.track('3');

    expect(store.get()).toEqual(['3', '6', '5', '4', '2']);
    expect(store.get()).toHaveLength(MAX_TRACKED_JOBS);
    expect(JSON.parse(storage.data.get(TRACKED_JOBS_KEY)!)).toEqual(['3', '6', '5', '4', '2']);
    expect(listener).toHaveBeenCalledTimes(7);

    // 이미 맨 앞이면 바꾸지 않는다(같은 스냅숏)
    const snapshot = store.get();
    store.track('3');
    expect(store.get()).toBe(snapshot);
  });

  it('작업 아이디가 아닌 값은 받지 않고, 저장소 값도 거른다', () => {
    const storage = memoryStorage({ [TRACKED_JOBS_KEY]: JSON.stringify(['7', 'x', 8, '-1']) });
    const store = createTrackedJobsStore(() => storage);
    expect(store.get()).toEqual(['7']);
    store.track('abc');
    expect(store.get()).toEqual(['7']);
    store.untrack('7');
    expect(store.get()).toEqual([]);
  });

  it('저장소를 못 쓰면 메모리에만 둔다', () => {
    const store = createTrackedJobsStore(() => ({
      getItem: () => {
        throw new Error('denied');
      },
      setItem: () => {
        throw new Error('denied');
      },
    }));
    store.track('9');
    expect(store.get()).toEqual(['9']);
  });
});
