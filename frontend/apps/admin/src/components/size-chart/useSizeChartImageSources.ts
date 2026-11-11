'use client';

import { apiFetch } from '@cf/api-client';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { AccessDenied } from '@/lib/api-errors';
import {
  approvedImages,
  createGenerations,
  imageSourcesFailure,
  imageSourcesPath,
  inspectionFailure,
  inspectionPath,
  toggleSelection,
  type ApprovedImage,
  type ImageInspection,
  type ImageSource,
  type InspectionState,
} from '@/lib/size-chart/draft-image';

/** 목록 세대 키 — 이미지 아이디(`IMG-` · `DESC-`)와 겹치지 않는다 */
const LIST = 'list';

export type ImageSourcesStatus = 'idle' | 'loading' | 'ready' | 'closed' | 'error';

export type SizeChartImageSourcesState = {
  status: ImageSourcesStatus;
  sources: ImageSource[];
  /** 목록 단위 안내(실패 · 닫힘 · 다시 읽음). 없으면 null */
  message: string | null;
  /** 고른 순서 = 생성 순서 */
  selected: string[];
  inspections: Record<string, InspectionState | undefined>;
  /** 고른 이미지가 모두 확인됐으면 승인 목록, 아니면 null */
  approved: ApprovedImage[] | null;
  /** 처음 한 번 읽는다(이미지 입력을 고를 때). 이미 읽었으면 아무것도 하지 않는다 */
  load: () => void;
  /** 목록을 다시 읽고, 더 쓸 수 없는 선택은 뺀다. `clearSelection` 이면 선택을 모두 비운다(112 · 113) */
  reload: (message?: string | null, options?: { clearSelection?: boolean }) => Promise<void>;
  /** 고르면 바로 확인(inspection)한다 — 확인값이 승인 대상이다 */
  toggle: (sourceId: string) => void;
  /** 고른 이미지를 모두 다시 확인한다(승인 뒤 바뀜 015 · 확인값 오류 114) */
  reinspect: () => void;
  /** 이미지 입력이 닫혔다(생성 응답 011) */
  close: (message: string) => void;
};

/**
 * AI 초안 IMAGE 입력의 후보 · 선택 · 확인 상태(#218, BE #217 PR #690). 목록은 이미지 입력을 고를 때 한 번 읽는다 —
 * 텍스트만 쓰는 운영자에게 추가 요청을 만들지 않는다. 확인은 이미지마다 최대 5MiB 를 읽으므로 고를 때 한 번만 한다.
 */
export function useSizeChartImageSources(
  productId: string,
  onDenied: (denied: AccessDenied) => void,
): SizeChartImageSourcesState {
  const [status, setStatus] = useState<ImageSourcesStatus>('idle');
  const [sources, setSources] = useState<ImageSource[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [inspections, setInspections] = useState<Record<string, InspectionState | undefined>>({});
  const deniedRef = useRef(onDenied);
  useEffect(() => {
    deniedRef.current = onDenied;
  }, [onDenied]);
  // 이미지별 확인 세대 · 목록 세대 — 빼거나 다시 요청하면 늦게 온 이전 응답을 버린다
  const generations = useRef(createGenerations()).current;
  const startedRef = useRef(false);
  // 선택은 ref 와 state 를 함께 바꾼다 — 같은 틱의 토글 · 확인 응답이 낡은 선택을 보지 않게
  const selectedRef = useRef<string[]>([]);
  const select = useCallback((update: (prev: string[]) => string[]) => {
    const next = update(selectedRef.current);
    selectedRef.current = next;
    setSelected(next);
  }, []);

  const forget = useCallback(
    (ids: readonly string[]) => {
      generations.bump(ids);
      setInspections((prev) => {
        const next = { ...prev };
        for (const id of ids) delete next[id];
        return next;
      });
    },
    [generations],
  );

  const close = useCallback(
    (next: string) => {
      forget(selectedRef.current);
      select(() => []);
      setStatus('closed');
      setMessage(next);
    },
    [forget, select],
  );

  const reload = useCallback(
    async (next: string | null = null, options: { clearSelection?: boolean } = {}) => {
      const mine = generations.start(LIST);
      if (options.clearSelection) {
        forget(selectedRef.current);
        select(() => []);
      }
      setStatus('loading');
      setMessage(null);
      try {
        const list = await apiFetch<ImageSource[]>(imageSourcesPath(productId));
        if (!generations.isCurrent(LIST, mine)) return;
        const usable = new Set(list.filter((s) => s.eligible).map((s) => s.sourceId));
        const dropped = selectedRef.current.filter((id) => !usable.has(id));
        if (dropped.length > 0) {
          forget(dropped);
          select((prev) => prev.filter((id) => usable.has(id)));
        }
        setSources(list);
        setStatus('ready');
        setMessage(next);
      } catch (cause) {
        if (!generations.isCurrent(LIST, mine)) return;
        const failure = imageSourcesFailure(cause);
        if (failure.kind === 'access') {
          setStatus('error');
          deniedRef.current(failure.denied);
        } else if (failure.kind === 'closed') close(failure.message);
        else {
          setStatus('error');
          setMessage(failure.message);
        }
      }
    },
    [productId, forget, close, select, generations],
  );

  const load = useCallback(() => {
    if (startedRef.current) return;
    startedRef.current = true;
    void reload();
  }, [reload]);

  const inspect = useCallback(
    async (sourceId: string) => {
      const mine = generations.start(sourceId);
      setInspections((prev) => ({ ...prev, [sourceId]: { status: 'checking' } }));
      try {
        const inspection = await apiFetch<ImageInspection>(inspectionPath(productId, sourceId));
        if (!generations.isCurrent(sourceId, mine)) return;
        setInspections((prev) => ({ ...prev, [sourceId]: { status: 'ok', inspection } }));
      } catch (cause) {
        if (!generations.isCurrent(sourceId, mine)) return;
        const failure = inspectionFailure(cause);
        if (failure.kind === 'access') {
          forget([sourceId]);
          select((prev) => prev.filter((id) => id !== sourceId));
          deniedRef.current(failure.denied);
        } else if (failure.kind === 'closed') close(failure.message);
        else if (failure.kind === 'reloadSources') {
          // 목록에 아직 남아 있어도 이 선택은 뺀다 — 확인 중 상태로 남지 않게
          forget([sourceId]);
          select((prev) => prev.filter((id) => id !== sourceId));
          void reload(failure.message);
        } else
          setInspections((prev) => ({
            ...prev,
            [sourceId]: { status: 'error', message: failure.message },
          }));
      }
    },
    [productId, forget, close, reload, select, generations],
  );

  const toggle = useCallback(
    (sourceId: string) => {
      const prev = selectedRef.current;
      const next = toggleSelection(prev, sourceId);
      if (next.length === prev.length) return;
      select(() => next);
      if (next.includes(sourceId)) void inspect(sourceId);
      else forget([sourceId]);
    },
    [inspect, forget, select],
  );

  const reinspect = useCallback(() => {
    for (const id of selectedRef.current) void inspect(id);
  }, [inspect]);

  return {
    status,
    sources,
    message,
    selected,
    inspections,
    approved: approvedImages(selected, inspections),
    load,
    reload,
    toggle,
    reinspect,
    close,
  };
}
