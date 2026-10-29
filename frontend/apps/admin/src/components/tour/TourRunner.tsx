'use client';

import 'driver.js/dist/driver.css';
import { driver } from 'driver.js';
import { usePathname } from 'next/navigation';
import { useEffect, useSyncExternalStore } from 'react';
import { nextTour, serverTourState, tourProgress } from '@/lib/tour/progress';
import { driveSteps, tourSelector } from '@/lib/tour/steps';
import { toursFor } from '@/lib/tour/tours';

/** 화면 요소가 그려지기를 기다리는 최대 시간. 목록 · 표는 API 응답 뒤에 그려진다 */
const WAIT_MS = 4000;

const exists = (target: string) => document.querySelector(tourSelector(target)) !== null;

/** 운영자가 입력 칸에 쓰고 있다 — 서버 기록이 늦게 와도 그 위로 투어를 띄우지 않는다 */
const typing = () => {
  const active = document.activeElement;
  return (
    active instanceof HTMLElement &&
    (active.isContentEditable || active.matches('input, textarea, select'))
  );
};

/**
 * 온보딩 투어 실행기(#228) — 콘솔 레이아웃에 하나 붙어 화면이 바뀔 때마다 이 화면에서 볼 투어를 고른다.
 * - 붙을 때(로그인 직후) 운영자별 서버 기록(BE #357)을 불러온다. 불러오기 전 · 불러오지 못하면 자동 시작하지 않는다.
 * - 처음이면 콘솔 투어 → 화면 투어 순서로 한 번씩 자동 시작한다. 시작하면 진행 중, 마지막 단계에서 닫으면 완료,
 *   그 전에 닫으면(×, Esc, 바깥 누르기) 건너뜀으로 남긴다.
 * - 도움말의 "다시 보기" 는 진행 상태에 요청을 남기고(`requestSeq` 가 오른다), 여기가 그 요청을 받아 시작한다.
 * - 가리킬 요소는 API 응답 뒤에 그려지므로 모두 나타날 때까지(최대 4초) 기다린다. 그때 필수 요소가 없으면 시작하지 않는다.
 *   **기다리는 동안 운영자가 누르거나 입력하면 이번에는 자동 시작하지 않는다** — 조작 중에 오버레이가 덮지 않게(다음에 다시 뜬다).
 *   시작할 때 입력 칸에 포커스가 있어도 마찬가지다(서버 기록이 늦게 온 경우). 다시 보기 요청은 운영자가 원한 것이라 이 규칙을 따르지 않는다.
 * - 화면을 옮기면 진행 중인 투어를 닫되 본 것으로 남기지 않는다(옮긴 화면에서 다시 고른다).
 * - 끝나면 투어를 시작하기 전의 포커스로 돌린다(그 요소가 아직 있으면).
 */
export function TourRunner() {
  const pathname = usePathname();
  const state = useSyncExternalStore(tourProgress.subscribe, tourProgress.get, serverTourState);
  const tour = nextTour(toursFor(pathname), state);
  const requestSeq = state.requestSeq;

  useEffect(() => {
    void tourProgress.refresh();
  }, []);

  useEffect(() => {
    if (!tour) return;
    const requested = tourProgress.get().requested === tour.id;
    let finished = false;
    let instance: ReturnType<typeof driver> | null = null;
    const targets = tour.steps.flatMap((step) => (step.target ? [step.target] : []));

    const stopWaiting = () => {
      observer.disconnect();
      window.clearTimeout(timer);
      document.removeEventListener('pointerdown', onUserInput, true);
      document.removeEventListener('keydown', onUserInput, true);
    };

    const start = () => {
      stopWaiting();
      if (finished) return;
      if (!requested && typing()) {
        finished = true;
        return;
      }
      const steps = driveSteps(tour, exists);
      if (!steps) {
        tourProgress.clearRequest();
        return;
      }
      const returnFocus = document.activeElement;
      const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      instance = driver({
        steps,
        animate: !reduced,
        smoothScroll: !reduced,
        showProgress: steps.length > 1,
        progressText: '{{current}} / {{total}}',
        nextBtnText: '다음',
        prevBtnText: '이전',
        doneBtnText: '마치기',
        closeBtnLabel: '안내 닫기',
        overlayOpacity: 0.5,
        stagePadding: 6,
        // 투어 중에는 가리킨 요소를 누르지 않게 한다 — 메뉴를 눌러 화면이 바뀌면 투어가 끊긴다
        disableActiveInteraction: true,
        popoverClass: 'cf-tour',
        // 운영자가 닫았다(마치기 · × · Esc · 바깥 누르기). 화면 이동 정리(destroy())는 여기를 지나지 않아 본 것으로 남지 않는다.
        // onDestroyed 는 단계 전환 애니메이션(requestAnimationFrame)이 끝나야 불려 쓰지 않는다
        onDestroyStarted: () => {
          if (finished) return;
          finished = true;
          instance?.destroy();
          // driver.js 는 단계마다 이전 팝오버 버튼(이미 빠진 노드)을 돌아갈 곳으로 잡아 포커스를 잃는다
          if (returnFocus instanceof HTMLElement && returnFocus.isConnected) returnFocus.focus();
          tourProgress.finish(
            tour.id,
            instance?.isLastStep() ? 'COMPLETED' : 'SKIPPED',
            instance?.getActiveIndex() ?? null,
          );
        },
      });
      instance.drive();
      tourProgress.start(tour.id);
    };

    /** 기다리는 동안 운영자가 화면을 쓰기 시작했다 — 이번 방문에는 자동으로 띄우지 않는다 */
    const onUserInput = () => {
      stopWaiting();
      finished = true;
    };

    const observer = new MutationObserver(() => {
      if (targets.every(exists)) start();
    });
    const ready = targets.every(exists);
    const timer = window.setTimeout(start, ready ? 0 : WAIT_MS);
    if (!ready) {
      observer.observe(document.body, { childList: true, subtree: true });
      if (!requested) {
        document.addEventListener('pointerdown', onUserInput, true);
        document.addEventListener('keydown', onUserInput, true);
      }
    }

    return () => {
      stopWaiting();
      // 화면을 옮겼거나 다음 투어로 넘어간다 — 닫되 본 것으로 남기지 않는다
      const unfinished = !finished;
      finished = true;
      if (instance?.isActive()) instance.destroy();
      // 다시 보기 도중 화면을 옮겼다 — 요청을 지워 옮긴 화면의 투어 고르기를 막지 않는다
      if (unfinished && tourProgress.get().requested === tour.id) tourProgress.clearRequest();
    };
    // 같은 투어라도 화면(경로)이 바뀌거나 다시 보기를 또 누르면 다시 시작한다
  }, [tour, pathname, requestSeq]);

  return null;
}
