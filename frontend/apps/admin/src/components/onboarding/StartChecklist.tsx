'use client';

import { Button } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useId, useSyncExternalStore } from 'react';
import { tourNoteOf, type TaskProgress, type TaskView } from '@/lib/onboarding/start-tasks';
import {
  onboardingTasks,
  serverStartCardPref,
  serverTasksState,
  startCardPref,
  type StartCardPref,
  type TasksState,
} from '@/lib/onboarding/tasks-store';
import { serverTourState, tourProgress, type TourState } from '@/lib/tour/progress';

/**
 * 어드민 시작 체크리스트(#358) — 처음 쓰는 운영자가 첫 업무(배송비 정책 저장 · 상품 등록)를 찾아 하도록 돕는 홈 카드.
 * - 업무 완료는 서버가 담당 서비스 사실로 판정한 값만 보인다. 카드에서 완료로 바꾸는 체크박스는 없다.
 * - 안내(투어)를 본 것은 업무 완료와 다른 문구로 따로 보인다.
 * - "안내와 함께 열기" 는 기존 투어 실행기(`TourRunner`)에 다시 보기 요청을 남기고 업무 화면으로 간다. 카드가 스스로 투어를 띄우지 않는다.
 * - 홈에 올 때마다 서버에서 다시 읽는다(다른 기기에서 한 업무 · 저장 직후 상태).
 */
export function StartChecklist() {
  const router = useRouter();
  const tasks = useSyncExternalStore(
    onboardingTasks.subscribe,
    onboardingTasks.get,
    serverTasksState,
  );
  const tours = useSyncExternalStore(tourProgress.subscribe, tourProgress.get, serverTourState);
  const pref = useSyncExternalStore(
    startCardPref.subscribe,
    startCardPref.get,
    serverStartCardPref,
  );

  useEffect(() => {
    void onboardingTasks.load();
  }, []);

  return (
    <StartChecklistView
      tasks={tasks}
      tours={tours}
      pref={pref}
      onPref={startCardPref.set}
      onRetry={() => void onboardingTasks.load()}
      onOpenWithTour={(view) => {
        if (view.task.tourId) tourProgress.request(view.task.tourId);
        router.push(view.task.href);
      }}
    />
  );
}

const PROGRESS_TEXT: Record<TaskProgress, string> = {
  done: '완료',
  storeOnly: '스토어에는 설정됨 · 내 저장 기록 없음',
  todo: '아직 하지 않음',
  unknown: '확인 불가 · 잠시 후 다시 확인해 주세요',
};

const PROGRESS_CLASS: Record<TaskProgress, string> = {
  done: 'text-primary',
  storeOnly: 'text-muted-fg',
  todo: 'text-muted-fg',
  unknown: 'text-danger',
};

export type StartChecklistViewProps = {
  tasks: TasksState;
  tours: TourState;
  pref: StartCardPref;
  onPref: (pref: StartCardPref) => void;
  onRetry: () => void;
  onOpenWithTour: (view: TaskView) => void;
};

export function StartChecklistView({
  tasks,
  tours,
  pref,
  onPref,
  onRetry,
  onOpenWithTour,
}: StartChecklistViewProps) {
  const titleId = useId();
  const bodyId = useId();
  const views = tasks.tasks;

  // 서버가 그릴 업무를 하나도 주지 않았다(업무가 아직 배포되지 않은 서버) — 카드를 두지 않는다
  if (views !== null && views.length === 0 && !tasks.loading && !tasks.failed) return null;

  if (pref === 'hidden') {
    return (
      <button
        type="button"
        onClick={() => onPref('open')}
        className="self-start rounded-md px-2 py-1 text-sm text-muted-fg underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-primary"
      >
        시작 체크리스트 보기
      </button>
    );
  }

  const collapsed = pref === 'collapsed';
  const doneCount = views?.filter((view) => view.progress === 'done').length ?? 0;

  let summary: string;
  if (tasks.loading) summary = '업무 상태를 확인하는 중…';
  else if (views === null)
    summary = tasks.failed
      ? '업무 상태를 확인하지 못했습니다. 아직 하지 않았다는 뜻은 아닙니다.'
      : '업무 상태를 확인하는 중…';
  else if (doneCount === views.length) summary = '시작 업무를 모두 마쳤습니다.';
  else summary = `${views.length}개 중 ${doneCount}개 완료`;

  return (
    <section
      aria-labelledby={titleId}
      className="flex flex-col gap-4 rounded-md border border-border bg-bg p-4 sm:p-5"
    >
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 id={titleId} className="font-semibold">
            시작하기
          </h2>
          <p role="status" className="mt-1 text-sm text-muted-fg">
            {summary}
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            variant="secondary"
            aria-expanded={!collapsed}
            aria-controls={bodyId}
            onClick={() => onPref(collapsed ? 'open' : 'collapsed')}
          >
            {collapsed ? '펼치기' : '접기'}
          </Button>
          <Button variant="secondary" onClick={() => onPref('hidden')}>
            나중에
          </Button>
        </div>
      </div>

      <div id={bodyId} hidden={collapsed} className="flex flex-col gap-3">
        {tasks.failed && (
          <div role="alert" className="flex flex-wrap items-center gap-3">
            <p className="text-sm text-danger">
              {views === null
                ? '잠시 뒤 다시 확인해 주세요.'
                : '최신 상태를 확인하지 못했습니다. 아래는 마지막으로 확인한 상태입니다.'}
            </p>
            <Button variant="secondary" onClick={onRetry}>
              다시 확인
            </Button>
          </div>
        )}

        {views !== null && views.length > 0 && (
          <ul className="flex flex-col gap-3">
            {views.map((view) => (
              <TaskItem
                key={view.key}
                view={view}
                checking={tasks.loading}
                tourNote={tourNoteOf(view.task.tourId, tours)}
                onOpenWithTour={() => onOpenWithTour(view)}
              />
            ))}
          </ul>
        )}

        <p className="text-xs text-muted-fg">
          업무는 실제로 저장(등록)해야 완료됩니다. 안내를 끝까지 보거나 건너뛴 것은 업무 완료가
          아닙니다.
        </p>
      </div>
    </section>
  );
}

function TaskItem({
  view,
  checking,
  tourNote,
  onOpenWithTour,
}: {
  view: TaskView;
  checking: boolean;
  tourNote: string | null;
  onOpenWithTour: () => void;
}) {
  const { task, progress } = view;
  return (
    <li className="flex flex-col gap-3 rounded-md bg-muted/50 p-4 sm:flex-row sm:items-start sm:justify-between">
      <div className="min-w-0">
        <p className="font-medium">{task.title}</p>
        <p
          className={`mt-0.5 text-sm font-medium ${checking ? 'text-muted-fg' : PROGRESS_CLASS[progress]}`}
        >
          {checking ? '확인 중…' : PROGRESS_TEXT[progress]}
        </p>
        <p className="mt-1 text-sm text-muted-fg">{task.description}</p>
        {tourNote && <p className="mt-1 text-xs text-muted-fg">{tourNote}</p>}
      </div>
      <div className="flex shrink-0 flex-wrap gap-2">
        {task.tourId && (
          <Button variant="secondary" onClick={onOpenWithTour}>
            안내와 함께 열기
          </Button>
        )}
        <Link
          href={task.href}
          className="inline-flex h-10 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-fg hover:opacity-90 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
        >
          {progress === 'done' ? '화면 보기' : '바로 하기'}
        </Link>
      </div>
    </li>
  );
}
