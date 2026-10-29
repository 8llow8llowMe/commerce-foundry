import { apiFetch } from '@cf/api-client';

/**
 * 운영자별 안내 투어 진행 API(BE #357, `backend/docs/api-design-guide.md` "운영자 온보딩 계약").
 * 운영자는 access 토큰으로만 정해진다 — 경로 · 본문에 운영자를 싣지 않는다.
 */
export const ONBOARDING_ME_PATH = '/api/v1/admin/onboarding/me';

export const TOUR_STATUSES = ['NOT_STARTED', 'IN_PROGRESS', 'COMPLETED', 'SKIPPED'] as const;
export type TourStatus = (typeof TOUR_STATUSES)[number];

/** 서버가 낸 투어 하나. `guideVersion` 은 지금 배포된 버전이다 */
export type ServerTourItem = {
  guideKey: string;
  guideVersion: number;
  status: { code: string };
  currentStep: number | null;
  finishedAt: string | null;
};

/**
 * 서버가 낸 업무 하나(#357 PR-B2). 업무 완료는 담당 서비스의 사실로만 판정한다 — 투어 기록과 다르다.
 * `taskKey` 는 화면 분기용 제어 코드이고 표시 이름은 FE 가 가진다(`lib/onboarding/start-tasks.ts`).
 */
export type ServerTaskItem = {
  taskKey: string;
  storeStatus: { code: string };
  myStatus: { code: string };
  myCompletedAt: string | null;
};

/** `tasks` 는 #357 PR-B2 전 서버에서는 빈 배열이다 */
export type OnboardingMe = { tours: ServerTourItem[]; tasks?: ServerTaskItem[] };

export type TourUpdate = {
  guideVersion: number;
  status: TourStatus;
  currentStep: number | null;
};

export type OnboardingApi = {
  getMine: () => Promise<OnboardingMe>;
  updateTour: (guideKey: string, body: TourUpdate) => Promise<unknown>;
};

export const onboardingApi: OnboardingApi = {
  getMine: () => apiFetch<OnboardingMe>(ONBOARDING_ME_PATH),
  updateTour: (guideKey, body) =>
    apiFetch(`${ONBOARDING_ME_PATH}/tours/${encodeURIComponent(guideKey)}`, {
      method: 'PUT',
      json: body,
    }),
};

/** 서버 안내 키 형식(`^[a-z][a-z0-9-]{0,63}$`) */
const GUIDE_KEY = /^[a-z][a-z0-9-]{0,63}$/;

/**
 * FE 투어 아이디(`console-v1`) → 서버 안내 키 · 버전(`console`, 1). 꼬리 `-v<숫자>` 가 없으면 서버에 기록하지 않는 투어다(null).
 */
export function guideOf(tourId: string): { key: string; version: number } | null {
  const match = /^(.+)-v(\d+)$/.exec(tourId);
  if (!match) return null;
  const key = match[1]!;
  const version = Number(match[2]);
  return GUIDE_KEY.test(key) && Number.isSafeInteger(version) && version >= 1
    ? { key, version }
    : null;
}

export function isTourStatus(value: unknown): value is TourStatus {
  return typeof value === 'string' && (TOUR_STATUSES as readonly string[]).includes(value);
}

export const isFinished = (status: TourStatus) => status === 'COMPLETED' || status === 'SKIPPED';
