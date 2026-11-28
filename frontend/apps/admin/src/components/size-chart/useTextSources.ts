'use client';

import { apiFetch } from '@cf/api-client';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { AccessDenied } from '@/lib/api-errors';
import { createGenerations } from '@/lib/size-chart/draft-image';
import {
  textSourcesFailure,
  textSourcesPath,
  type TextSource,
  type TextSourcesState,
} from '@/lib/size-chart/text-source';

export type TextSourcesStore = {
  stateOf: (productId: string) => TextSourcesState;
  /** 후보를 (다시) 읽는다. 읽은 목록을 돌려주고, 실패 · 늦게 온 응답이면 null */
  load: (productId: string) => Promise<TextSource[] | null>;
};

/**
 * 상품별 본문 치수표 후보(#218 · BE #737). 단건 패널은 상품 하나, 일괄 화면은 고른 상품마다 쓴다.
 * 같은 상품을 다시 읽으면 늦게 온 이전 응답은 버린다. 403 은 화면의 권한 안내로 올린다.
 */
export function useTextSources(onDenied: (denied: AccessDenied) => void): TextSourcesStore {
  const [states, setStates] = useState<Readonly<Record<string, TextSourcesState>>>({});
  const [generations] = useState(createGenerations);
  const deniedRef = useRef(onDenied);
  useEffect(() => {
    deniedRef.current = onDenied;
  }, [onDenied]);

  const load = useCallback(
    async (productId: string): Promise<TextSource[] | null> => {
      const put = (id: string, state: TextSourcesState) =>
        setStates((current) => ({ ...current, [id]: state }));
      const generation = generations.start(productId);
      put(productId, { status: 'loading' });
      try {
        const sources = await apiFetch<TextSource[]>(textSourcesPath(productId));
        if (!generations.isCurrent(productId, generation)) return null;
        put(productId, { status: 'ok', sources });
        return sources;
      } catch (cause) {
        if (!generations.isCurrent(productId, generation)) return null;
        const failure = textSourcesFailure(cause);
        if (failure.kind === 'access') {
          put(productId, { status: 'idle' });
          deniedRef.current(failure.denied);
        } else put(productId, { status: 'error', message: failure.message });
        return null;
      }
    },
    [generations],
  );

  const stateOf = useCallback(
    (productId: string): TextSourcesState => states[productId] ?? { status: 'idle' },
    [states],
  );

  return { stateOf, load };
}
