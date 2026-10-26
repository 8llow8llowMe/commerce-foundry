import { describe, expect, it } from 'vitest';
import { createTourProgressStore, nextTour, TOUR_PROGRESS_KEY } from './progress';

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => void data.set(key, value),
    data,
  };
}

const A = { id: 'a' };
const B = { id: 'b' };

describe('createTourProgressStore', () => {
  it('저장소의 본 투어를 읽고, 이상한 값은 버린다', () => {
    const storage = memoryStorage({
      [TOUR_PROGRESS_KEY]: JSON.stringify(['a', 3, 'x'.repeat(65)]),
    });
    const store = createTourProgressStore(() => storage);
    expect([...store.get().seen]).toEqual(['a']);
    expect(
      createTourProgressStore(() => memoryStorage({ [TOUR_PROGRESS_KEY]: '{' })).get().seen.size,
    ).toBe(0);
  });

  it('끝내면 본 것으로 저장하고 요청을 지운다. 바뀌지 않으면 같은 스냅숏이다', () => {
    const storage = memoryStorage();
    const store = createTourProgressStore(() => storage);
    let calls = 0;
    store.subscribe(() => (calls += 1));
    store.request('a');
    expect(store.get()).toMatchObject({ requested: 'a', requestSeq: 1 });
    store.finish('a');
    expect(store.get()).toEqual({ seen: new Set(['a']), requested: null, requestSeq: 1 });
    expect(JSON.parse(storage.data.get(TOUR_PROGRESS_KEY)!)).toEqual(['a']);
    const snapshot = store.get();
    store.finish('a');
    store.clearRequest();
    expect(store.get()).toBe(snapshot);
    expect(calls).toBe(2);
  });

  it('같은 투어를 또 요청하면 요청 번호가 오른다(실행기가 다시 시작한다)', () => {
    const store = createTourProgressStore(() => memoryStorage());
    store.request('a');
    store.request('a');
    expect(store.get().requestSeq).toBe(2);
  });

  it('요청만 지울 때는 저장소에 쓰지 않는다', () => {
    const storage = memoryStorage({ [TOUR_PROGRESS_KEY]: '["b"]' });
    const store = createTourProgressStore(() => storage);
    store.request('a');
    storage.data.set(TOUR_PROGRESS_KEY, '["b","x"]');
    store.clearRequest();
    expect(store.get().requested).toBeNull();
    expect(storage.data.get(TOUR_PROGRESS_KEY)).toBe('["b","x"]');
  });

  it('다른 탭이 남긴 기록을 덮어쓰지 않고 합친다', () => {
    const storage = memoryStorage();
    const store = createTourProgressStore(() => storage);
    store.get();
    storage.data.set(TOUR_PROGRESS_KEY, '["other"]');
    store.finish('a');
    expect(JSON.parse(storage.data.get(TOUR_PROGRESS_KEY)!).sort()).toEqual(['a', 'other']);
  });

  it('다시 보기 요청은 본 표시를 지운다. 초기화는 모두 지운다', () => {
    const store = createTourProgressStore(() =>
      memoryStorage({ [TOUR_PROGRESS_KEY]: '["a","b"]' }),
    );
    store.request('a');
    expect([...store.get().seen]).toEqual(['b']);
    store.reset();
    expect(store.get()).toMatchObject({ seen: new Set(), requested: null, requestSeq: 1 });
  });

  it('저장소를 못 쓰면 메모리에만 기억한다', () => {
    const store = createTourProgressStore(() => ({
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    }));
    store.finish('a');
    expect(store.get().seen.has('a')).toBe(true);
  });
});

describe('nextTour', () => {
  it('안 본 첫 투어, 요청이 이 화면 투어면 그것, 다른 화면 요청은 무시한다', () => {
    expect(nextTour([A, B], { seen: new Set(), requested: null, requestSeq: 0 })).toBe(A);
    expect(nextTour([A, B], { seen: new Set(['a']), requested: null, requestSeq: 0 })).toBe(B);
    expect(
      nextTour([A, B], { seen: new Set(['a', 'b']), requested: null, requestSeq: 0 }),
    ).toBeNull();
    expect(nextTour([A, B], { seen: new Set(['b']), requested: 'b', requestSeq: 0 })).toBe(B);
    expect(
      nextTour([A, B], { seen: new Set(['a', 'b']), requested: 'z', requestSeq: 0 }),
    ).toBeNull();
  });
});
