'use client';

import { apiFetch, createIdempotencyKey, type PageResponse } from '@cf/api-client';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { AccessDenied } from '@/lib/api-errors';
import { isActiveJob, isJobId, jobApiPath, pollDelay } from '@/lib/job/detail';
import { trackedJobs } from '@/lib/job/tracked-jobs';
import type { JobItem } from '@/lib/job/types';
import {
  createDraftPath,
  draftCreateFailure,
  draftPath,
  draftReadFailure,
  keyFor,
  recentDraftsPath,
  refreshDraftPath,
  resumableDraft,
  statusOf,
  type DraftAccepted,
  type DraftKey,
  type DraftSummary,
  type SizeChartDraft,
} from '@/lib/size-chart/draft';
import { draftBody, type DraftInput, type ImageFollowUp } from '@/lib/size-chart/draft-image';

/** 생성 중인 초안의 작업을 상단 작업 표시(#214)에 올린다 — 끝나면 알림 센터로도 알린다 */
function watch(next: SizeChartDraft) {
  if (statusOf(next) === 'REQUESTED' && next.jobId && isJobId(next.jobId))
    trackedJobs.track(next.jobId);
}

/** 작업이 끝났는데 새로고침이 아직 REQUESTED 를 돌려줄 때 다시 물어볼 횟수 */
const MAX_REFRESH_MISSES = 4;

export const JOB_WATCH_UNAVAILABLE =
  '생성 진행 상태를 자동으로 확인하지 못합니다. 완료 알림을 받은 뒤 결과 확인을 눌러 주세요.';
export const STILL_RUNNING = '아직 생성 중입니다. 완료되면 알림 센터로 알려 드립니다.';

export type SizeChartDraftState = {
  draft: SizeChartDraft | null;
  /** 패널 안내(실패 · 진행). 없으면 null */
  message: string | null;
  /** 입력 칸 오류(치수 텍스트 · 이미지 선택) */
  sourceError: string | null;
  submitting: boolean;
  checking: boolean;
  /**
   * 생성 요청. 같은 템플릿 · 입력의 재요청(응답 유실 · 두 번 누름)은 같은 멱등 키다.
   * IMAGE 입력의 후속 처리(기능 닫힘 · 후보 다시 읽기 · 다시 확인)가 필요하면 그 동작을 돌려준다 — 이미지 상태는 패널이 가진다.
   */
  request: (templateId: string, input: DraftInput) => Promise<ImageFollowUp | null>;
  /** 결과 확인(refresh). 작업이 끝났으면 결과를 초안에 반영한다 */
  check: () => Promise<void>;
  /** 저장된 초안을 다시 읽는다(적용 충돌 뒤) */
  reload: () => Promise<SizeChartDraft | null>;
  /** 이 초안을 놓고 새로 요청할 수 있게 한다. 다음 요청은 새 키다 */
  dismiss: (message?: string | null) => void;
  clearSourceError: () => void;
};

/**
 * 상품 하나의 AI 사이즈표 초안 흐름(#218). 진행은 작업 상세를 다시 읽어 알고(#212), 상단 작업 표시 · 알림 센터(#214)에도
 * 이 작업을 올린다. 초안 상세 GET 은 작업 결과를 가져오지 않아, 작업이 끝났을 때 한 번 refresh 해 결과를 반영한다.
 */
export function useSizeChartDraft(
  productId: string,
  onDenied: (denied: AccessDenied) => void,
): SizeChartDraftState {
  const [draft, setDraft] = useState<SizeChartDraft | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [sourceError, setSourceError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [checking, setChecking] = useState(false);
  const keyRef = useRef<DraftKey | null>(null);
  const submittingRef = useRef(false);
  const checkingRef = useRef(false);
  const deniedRef = useRef(onDenied);
  useEffect(() => {
    deniedRef.current = onDenied;
  }, [onDenied]);

  const readFailed = useCallback((cause: unknown): null => {
    const failure = draftReadFailure(cause);
    if (failure.kind === 'access') deniedRef.current(failure.denied);
    else if (failure.kind === 'gone') {
      setDraft(null);
      keyRef.current = null;
      setMessage(failure.message);
    } else setMessage(failure.message);
    return null;
  }, []);

  const reload = useCallback(async (): Promise<SizeChartDraft | null> => {
    if (!draft) return null;
    try {
      const next = await apiFetch<SizeChartDraft>(draftPath(draft.draftId));
      setDraft(next);
      return next;
    } catch (cause) {
      return readFailed(cause);
    }
  }, [draft, readFailed]);

  /** refresh 한 번. 결과가 아직 없으면 REQUESTED 그대로 돌아온다 */
  const refresh = useCallback(
    async (draftId: string): Promise<SizeChartDraft | null> => {
      if (checkingRef.current) return null;
      checkingRef.current = true;
      setChecking(true);
      try {
        const next = await apiFetch<SizeChartDraft>(refreshDraftPath(draftId), { method: 'POST' });
        setDraft(next);
        setMessage(statusOf(next) === 'REQUESTED' ? STILL_RUNNING : null);
        return next;
      } catch (cause) {
        return readFailed(cause);
      } finally {
        checkingRef.current = false;
        setChecking(false);
      }
    },
    [readFailed],
  );

  const check = useCallback(async () => {
    if (draft) await refresh(draft.draftId);
  }, [draft, refresh]);

  // 들어올 때 이 상품에서 내가 요청한 최근 초안(생성 중 · 검토 대기)을 이어 본다 — 새로 고침 · 다른 화면에 다녀와도
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const list = await apiFetch<PageResponse<DraftSummary>>(recentDraftsPath(productId));
        const found = resumableDraft(list.contents, Date.now());
        if (!found || !alive) return;
        const next = await apiFetch<SizeChartDraft>(draftPath(found.draftId));
        if (!alive) return;
        setDraft(next);
        watch(next);
      } catch {
        // 이어 보기는 덤이다 — 실패해도 새 요청은 할 수 있다
      }
    })();
    return () => {
      alive = false;
    };
  }, [productId]);

  // 생성 중이면 작업 상세를 다시 읽다가, 끝나면 refresh 로 결과를 초안에 반영한다
  const draftId = draft?.draftId ?? null;
  const jobId = draft?.jobId ?? null;
  const requested = draft !== null && statusOf(draft) === 'REQUESTED';
  useEffect(() => {
    // 작업 아이디가 없으면 자동 확인 없이 수동 확인만 둔다(안내는 아래 message 에서 고른다)
    if (!requested || !draftId || !jobId || !isJobId(jobId)) return;
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let failures = 0;
    let misses = 0;
    const schedule = () => {
      if (alive) timer = setTimeout(tick, pollDelay(failures));
    };
    const tick = async () => {
      if (typeof document !== 'undefined' && document.hidden) return schedule();
      try {
        const job = await apiFetch<JobItem>(jobApiPath(jobId));
        failures = 0;
        if (!alive) return;
        if (isActiveJob(job)) return schedule();
        const next = await refresh(draftId);
        if (!alive || !next || statusOf(next) !== 'REQUESTED') return;
        misses += 1;
        if (misses >= MAX_REFRESH_MISSES) return;
        failures = misses;
        schedule();
      } catch {
        failures += 1;
        // 작업 화면 권한이 없거나 계속 실패하면 자동 확인을 멈추고 수동 확인으로 둔다
        if (failures >= 4) {
          if (alive) setMessage(JOB_WATCH_UNAVAILABLE);
          return;
        }
        schedule();
      }
    };
    schedule();
    return () => {
      alive = false;
      if (timer) clearTimeout(timer);
    };
  }, [requested, draftId, jobId, refresh]);

  const request = useCallback(
    async (templateId: string, input: DraftInput): Promise<ImageFollowUp | null> => {
      if (submittingRef.current) return null;
      submittingRef.current = true;
      setSubmitting(true);
      setMessage(null);
      setSourceError(null);
      // 끝난 초안(실패 · 취소 · 적용)이 보이는 중의 요청은 새 의도다 — 같은 키면 서버가 그 초안을 그대로 돌려준다
      if (draft) keyRef.current = null;
      const key = keyFor(keyRef.current, templateId, input, createIdempotencyKey);
      keyRef.current = key;
      try {
        const accepted = await apiFetch<DraftAccepted>(createDraftPath(productId), {
          method: 'POST',
          idempotencyKey: key.key,
          json: draftBody(templateId, input),
        });
        if (isJobId(accepted.jobId)) trackedJobs.track(accepted.jobId);
        try {
          setDraft(await apiFetch<SizeChartDraft>(draftPath(accepted.draftId)));
        } catch (cause) {
          readFailed(cause);
        }
        return null;
      } catch (cause) {
        const failure = draftCreateFailure(cause, input.mode);
        if (failure.kind === 'access') deniedRef.current(failure.denied);
        else if (failure.kind === 'source') setSourceError(failure.message);
        else if (failure.kind === 'image') {
          setSourceError(failure.message);
          return { action: failure.action, message: failure.message };
        } else {
          if (failure.kind === 'newKey') keyRef.current = null;
          setMessage(failure.message);
        }
        return null;
      } finally {
        submittingRef.current = false;
        setSubmitting(false);
      }
    },
    [draft, productId, readFailed],
  );

  const dismiss = useCallback((next: string | null = null) => {
    setDraft(null);
    keyRef.current = null;
    setMessage(next);
  }, []);

  const clearSourceError = useCallback(() => setSourceError(null), []);

  const unwatchable = requested && !(jobId && isJobId(jobId));
  return {
    draft,
    message: message ?? (unwatchable ? JOB_WATCH_UNAVAILABLE : null),
    sourceError,
    submitting,
    checking,
    request,
    check,
    reload,
    dismiss,
    clearSourceError,
  };
}
