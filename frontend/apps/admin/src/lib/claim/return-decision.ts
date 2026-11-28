import { ApiError, parseMoneyInput, type Money } from '@cf/api-client';
import { accessDeniedOf } from '@/lib/api-errors';
import { REASON_INVALID_CHARACTER, REASON_REQUIRED, REASON_TOO_LONG } from './decision';
import {
  adminClaimReturnApprovalPath,
  adminClaimReturnRejectionPath,
  RETURN_FAULTS,
  type ClaimDetail,
  type ReturnDecision,
  type ReturnFaultCode,
  type ReturnRefundOption,
} from './types';

/**
 * 반품 승인 · 거절(#30 · BE #17 B2c-2) — 권한 `ORDER_MANAGE`(ADMIN · OWNER · MANAGER).
 * 둘 다 멱등 키가 없고 본문의 `expectedVersion`(상세 `recovery.version`)으로 겹친 요청을 막는다 — 같은 화면 버전으로 두 번 보내면 두 번째는 409 `ORDER_037` 이다.
 * 할 수 있는지의 정본은 서버의 `returnDecision.approvable` · `rejectable` 과 권한이다. 응답은 둘 다 현재 상세다.
 */
export type ReturnAction = 'approve' | 'reject';

export const RETURN_ACTION_LABEL: Record<ReturnAction, string> = {
  approve: '반품 승인',
  reject: '반품 거절',
};

export const returnActionPath = (action: ReturnAction, claimId: string) =>
  action === 'approve'
    ? adminClaimReturnApprovalPath(claimId)
    : adminClaimReturnRejectionPath(claimId);

/** 반품 결정 칸에서 지금 보일 것. 역할을 모르면(null) 숨기지 않는다 — 서버 403 안내에 맡긴다 */
export type ReturnPanelMode = {
  /** 승인 입력(귀책 · 재고 · 차감액)과 승인 버튼 */
  approve: boolean;
  /** 거절 버튼 */
  reject: boolean;
  /** 서버는 처리할 수 있다고 보지만 이 운영자에게 주문 관리 권한이 없다 — 버튼 대신 안내 */
  needsManager: boolean;
};

const NO_PANEL: ReturnPanelMode = { approve: false, reject: false, needsManager: false };

export function returnPanelMode(claim: ClaimDetail, canManage: boolean | null): ReturnPanelMode {
  const decision = claim.type.code === 'RETURN' ? claim.returnDecision : null;
  if (!decision) return NO_PANEL;
  const possible = decision.approvable || decision.rejectable;
  if (canManage === false) return { ...NO_PANEL, needsManager: possible };
  return { approve: decision.approvable, reject: decision.rejectable, needsManager: false };
}

export const RETURN_MANAGER_ONLY =
  '반품 승인 · 거절은 주문 관리 권한이 있는 운영자(대표 관리자 · 매니저)만 처리할 수 있습니다. 필요하면 권한이 있는 운영자에게 요청해 주세요.';

const isFault = (code: string): code is ReturnFaultCode =>
  (RETURN_FAULTS as readonly string[]).includes(code);

export type FaultOption = ReturnRefundOption & { code: ReturnFaultCode };

/** 고를 수 있는 귀책 — 서버가 준 순서(CUSTOMER · SELLER) 그대로, 모르는 귀책은 뺀다 */
export function faultOptions(decision: ReturnDecision): FaultOption[] {
  return decision.refundByFault.flatMap((option) => {
    const code = option.fault.code;
    return isFault(code) ? [{ ...option, code }] : [];
  });
}

/** 처음 고를 귀책 — 서버의 기본 귀책. 그 금액이 없으면 첫 귀책, 고를 것이 없으면 null */
export function initialFault(decision: ReturnDecision): ReturnFaultCode | null {
  const options = faultOptions(decision);
  const preferred = options.find((option) => option.code === decision.defaultFault.code);
  return (preferred ?? options[0])?.code ?? null;
}

/** 고른 귀책의 정책 환불액(차감 전) — 서버 값 그대로다 */
export function policyRefundOf(
  decision: ReturnDecision,
  fault: ReturnFaultCode | null,
): Money | null {
  return decision.refundByFault.find((option) => option.fault.code === fault)?.amount ?? null;
}

export const DEDUCTION_INVALID =
  '차감액은 0 이상 숫자로 적어 주세요. 원화는 원 단위 정수, 달러는 센트(소수 둘째 자리)까지입니다.';

/**
 * 차감액 입력 → 금액. 통화는 정책 환불액의 통화를 그대로 쓴다(주문 통화 — 다르면 서버 409 `ORDER_046`).
 * 비면 0 이다. 음수 · 소수 자릿수 초과 · 숫자 아님은 막는다(`parseMoneyInput`, 표기 변환이지 계산이 아니다).
 */
export function deductionOf(
  text: string,
  currency: Money['currency'],
): { ok: true; money: Money } | { ok: false; message: string } {
  const amount = parseMoneyInput(text, currency);
  if (amount === null) return { ok: true, money: { amount: 0, currency } };
  if (Number.isNaN(amount)) return { ok: false, message: DEDUCTION_INVALID };
  return { ok: true, money: { amount, currency } };
}

/**
 * 환불 예정액 = 고른 귀책의 정책 환불액 − 차감액.
 *
 * "클라이언트에서 금액을 계산하지 않는다"(frontend/docs/api-client.md §5)의 **유일한 예외**다. 반품 승인 계약
 * (backend/docs/api-design-guide.md §2-16 "반품 승인 · 거절")이 본문 `expectedRefundAmount` 를 "화면에서 본, 고른 귀책의 `refundByFault` 금액 − 차감액"으로
 * 정해 두었고, 서버가 주문을 잠근 뒤 같은 산식으로 다시 계산해 다르면 409 `ORDER_046`, 0 이하면 `ORDER_044` 로 거절한다.
 * 즉 이 값은 서버가 대조하는 **확인 값**이지 정본이 아니다. 두 값 모두 같은 통화의 최소 단위 정수라 뺄셈만 한다.
 */
export function expectedRefundOf(policy: Money, deduction: Money): Money {
  return { amount: policy.amount - deduction.amount, currency: policy.currency };
}

/** 승인 본문 — 서버 `ClaimReturnApprovalRequest` */
export type ReturnApprovalRequest = {
  expectedVersion: number;
  fault: ReturnFaultCode;
  restock: boolean;
  deductionAmount: Money;
  expectedRefundAmount: Money;
};

export type ApprovalField = 'fault' | 'restock' | 'deduction';

export type ApprovalBlocker = {
  /** 고칠 칸. null 은 칸이 아닌 이유(서버 판정) */
  field: ApprovalField | null;
  message: string;
  /** 환불할 금액이 0원 이하다 — 누르기 전에도 내역 아래에 바로 보인다 */
  notPayable: boolean;
};

export const FAULT_REQUIRED = '귀책을 골라 주세요.';
export const RESTOCK_REQUIRED = '검수 결과에 따라 재고로 되돌릴지 골라 주세요.';
export const POLICY_NOT_PAYABLE =
  '이 귀책으로는 정책 환불액이 0원 이하라 승인할 수 없습니다. 귀책을 다시 확인해 주세요.';
export const REFUND_NOT_PAYABLE =
  '환불 예정액이 0원 이하라 승인할 수 없습니다. 차감액이나 귀책을 다시 확인해 주세요.';
export const NOT_APPROVABLE = '지금은 이 반품을 승인할 수 없습니다.';

export type ApprovalInput = {
  claim: ClaimDetail;
  fault: ReturnFaultCode | null;
  /** 재고로 되돌릴지 — 서버 필수 값이라 고르기 전(null)에는 승인하지 않는다 */
  restock: boolean | null;
  deductionText: string;
};

/** 승인 칸의 내역과 보낼 본문. `blocker` 가 있으면 `request` 는 null 이다 */
export type ApprovalPlan = {
  policy: Money | null;
  /** 차감액 — 입력이 틀렸으면 null */
  deduction: Money | null;
  /** 환불 예정액 — 정책 환불액 · 차감액이 모두 있을 때만 */
  refund: Money | null;
  deductionError: string | null;
  blocker: ApprovalBlocker | null;
  request: ReturnApprovalRequest | null;
};

/** 승인 칸 상태 → 내역 · 막는 이유 · 본문. 막는 이유는 위 칸부터 하나만 낸다(정본 판정은 서버가 다시 한다) */
export function approvalPlan({
  claim,
  fault,
  restock,
  deductionText,
}: ApprovalInput): ApprovalPlan {
  const decision = claim.returnDecision;
  const policy = decision ? policyRefundOf(decision, fault) : null;
  const parsed = policy ? deductionOf(deductionText, policy.currency) : null;
  const deduction = parsed?.ok ? parsed.money : null;
  const deductionError = parsed && !parsed.ok ? parsed.message : null;
  const refund = policy && deduction ? expectedRefundOf(policy, deduction) : null;
  const plan = { policy, deduction, refund, deductionError };

  const blocked = (field: ApprovalField | null, message: string, notPayable = false) => ({
    ...plan,
    blocker: { field, message, notPayable },
    request: null,
  });
  if (!decision || !decision.approvable)
    return blocked(null, decision?.reason?.description || NOT_APPROVABLE);
  if (!fault || !policy) return blocked('fault', FAULT_REQUIRED);
  if (policy.amount <= 0) return blocked('fault', POLICY_NOT_PAYABLE, true);
  if (deductionError || !deduction || !refund)
    return blocked('deduction', deductionError ?? DEDUCTION_INVALID);
  if (refund.amount <= 0) return blocked('deduction', REFUND_NOT_PAYABLE, true);
  if (restock === null) return blocked('restock', RESTOCK_REQUIRED);
  return {
    ...plan,
    blocker: null,
    request: {
      expectedVersion: claim.recovery.version,
      fault,
      restock,
      deductionAmount: deduction,
      expectedRefundAmount: refund,
    },
  };
}

export const REJECT_DONE = '반품을 거절했습니다. 환불 없이 닫혔고 거절 사유가 남았습니다.';

/**
 * 승인 응답(현재 상세)의 결과 문구. **200 이어도 결제사가 환불을 거절했을 수 있다** — 응답의 `status` 로 고른다.
 * 처리 상황(`recovery.situation`)은 화면이 응답 그대로 다시 그린다.
 */
export function approvalOutcome(claim: ClaimDetail): { tone: 'info' | 'error'; message: string } {
  switch (claim.status.code) {
    case 'COMPLETED':
      return { tone: 'info', message: '반품을 승인하고 환불까지 마쳤습니다.' };
    case 'REFUNDED':
      return {
        tone: 'info',
        message: '반품을 승인하고 환불했습니다. 재고 되돌리기는 이어서 자동으로 진행합니다.',
      };
    case 'REFUND_FAILED':
      return {
        tone: 'error',
        message:
          '반품은 승인했지만 환불이 처리되지 않았습니다. 아래 처리 상황을 확인해 주세요. 결제사 화면에서 같은 건을 따로 환불하지 마세요.',
      };
    case 'RESTORATION_FAILED':
      return {
        tone: 'error',
        message:
          '반품을 승인하고 환불했지만 재고를 되돌리지 못했습니다. 아래 처리 상황을 확인해 주세요.',
      };
    default:
      return {
        tone: 'info',
        message:
          '반품을 승인했습니다. 환불은 이어서 자동으로 진행하니 아래 처리 상황을 확인해 주세요.',
      };
  }
}

/** 서버 처리 결과를 모르는 실패 — 승인은 돈이 나갔을 수 있으니 다시 누르지 말고 다시 불러온 상태를 보게 한다 */
export const RETURN_UNKNOWN =
  '처리 결과를 확인하지 못했습니다. 최신 상태를 다시 불러왔으니 상태 · 처리 상황을 먼저 확인해 주세요.';
export const RETURN_FAILED = '처리하지 못했습니다. 최신 상태를 다시 불러왔습니다.';
export const RETURN_RATE_LIMITED = '요청이 너무 많습니다. 잠시 뒤 다시 눌러 주세요.';
/** 쓰기 401 — 서버가 처리하지 않은 요청이라 다시 로그인한 뒤 다시 눌러도 안전하다(`DECISION_SESSION_EXPIRED` 와 같은 규칙) */
export const RETURN_SESSION_EXPIRED =
  '로그인이 만료되어 처리하지 못했습니다. 입력한 내용은 그대로 두었습니다. 새 탭에서 어드민에 다시 로그인한 뒤 다시 눌러 주세요.';

/**
 * 알려진 거절 → 운영자 문구. 원시 코드는 보이지 않는다. `reload` 는 서버 상태가 화면과 다를 수 있어 상세를 다시 읽는지다.
 * 판정 409 는 미리보기 `reason` 과 짝이다 — 042 `NOT_DECIDABLE` · 043 `RETURN_IN_FLIGHT` · 045 `NOT_CALCULABLE` · 503 040 `REFUND_NOT_OPEN`.
 */
const KNOWN: Record<string, { message: string; reload: boolean }> = {
  ORDER_037: {
    message:
      '다른 곳에서 이 반품이 먼저 처리되었거나 바뀌었습니다. 최신 내용을 다시 불러왔으니 확인한 뒤 다시 처리해 주세요.',
    reload: true,
  },
  ORDER_042: {
    message:
      '이 반품은 지금 승인하거나 거절할 수 없는 상태입니다. 최신 반품 · 주문 상태를 다시 불러왔습니다.',
    reload: true,
  },
  ORDER_043: {
    message:
      '같은 주문의 다른 반품 환불이 아직 끝나지 않았습니다. 그 반품을 먼저 마무리한 뒤 승인해 주세요.',
    reload: true,
  },
  ORDER_044: {
    message: '환불할 금액이 0원 이하라 승인하지 않았습니다. 최신 환불액을 다시 불러왔습니다.',
    reload: true,
  },
  ORDER_045: {
    message:
      '주문 · 결제 기록이 맞지 않아 환불액을 계산할 수 없습니다. 시스템 관리자에게 확인을 요청해 주세요.',
    reload: true,
  },
  ORDER_046: {
    message:
      '환불액이 화면에서 본 금액과 달라 승인하지 않았습니다. 최신 환불액을 다시 불러왔으니 확인한 뒤 다시 승인해 주세요.',
    reload: true,
  },
  ORDER_040: {
    message:
      '지금은 자동 환불 처리가 꺼져 있어 반품을 승인할 수 없습니다. 시스템 관리자에게 확인을 요청해 주세요.',
    reload: true,
  },
  ORDER_036: {
    message:
      '환불 결과를 주문에 반영하지 못했습니다. 다시 누르지 말고 잠시 뒤 상태 · 처리 상황을 확인해 주세요.',
    reload: true,
  },
  ORDER_032: { message: '없는 클레임입니다. 목록에서 다시 찾아 주세요.', reload: true },
  /** 화면 버전 형식 — 화면이 낡았다 */
  ORDER_169: {
    message: '화면 정보가 오래되었습니다. 최신 내용을 다시 불러왔습니다.',
    reload: true,
  },
  /** 모르는 귀책 · 통화 */
  ORDER_100: {
    message: '보낸 값을 처리하지 못했습니다. 최신 내용을 다시 불러왔으니 다시 확인해 주세요.',
    reload: true,
  },
};

/** 칸에 붙이는 400 — 확인 단계를 닫고 그 칸을 고쳐 다시 보내게 한다 */
const FIELD_CODES: Record<string, { field: ApprovalField | 'reason'; message: string }> = {
  ORDER_171: { field: 'restock', message: RESTOCK_REQUIRED },
  ORDER_172: { field: 'deduction', message: DEDUCTION_INVALID },
  ORDER_173: { field: 'deduction', message: REFUND_NOT_PAYABLE },
  ORDER_170: { field: 'reason', message: REASON_REQUIRED },
  ORDER_148: { field: 'reason', message: REASON_TOO_LONG },
  ORDER_151: { field: 'reason', message: REASON_INVALID_CHARACTER },
};

export type ReturnFailure =
  /** 403 — 공통 403 안내(`AccessNotice`) */
  | { kind: 'access'; denied: 'forbidden' }
  /** 칸 오류 — 입력은 그대로 두고 칸 아래에 보인다 */
  | { kind: 'field'; field: ApprovalField | 'reason'; message: string }
  /** 쓰기 401 — 입력을 지키려고 로그인 화면으로 보내지 않는다 */
  | { kind: 'form'; message: string }
  /** 칸 밖 결과 문구. `reload` 면 상세를 다시 읽는다 */
  | { kind: 'inline'; message: string; reload: boolean };

/**
 * 승인 · 거절 실패 → 화면 처리. 결과 모름(502 · 504 · 연결 끊김)과 알 수 없는 실패는 상세를 다시 읽어 실제 상태를 보인다.
 * **승인은 같은 요청에서 결제사 환불까지 이어 가므로** 결과를 확신할 수 없는 실패(ApiError 가 아닌 오류 · 500 · 봉투 아님 · 코드 없는 503 · 모르는 코드)를
 * "처리하지 못했습니다" 로 단정하지 않고 결과 모름(`RETURN_UNKNOWN`)으로 안내한다. 거절은 환불이 없어 공통 실패 문구다.
 * 429 는 게이트웨이가 서비스에 닿기 전에 막은 요청이라(처리되지 않음) 자동 재시도 · 쿨다운 없이 다시 누르라는 안내만 둔다 — 기존 `ClaimDecisions` 와 같다.
 */
export function returnFailure(cause: unknown, action: ReturnAction): ReturnFailure {
  const uncertain = action === 'approve' ? RETURN_UNKNOWN : RETURN_FAILED;
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return { kind: 'form', message: RETURN_SESSION_EXPIRED };
  if (denied === 'forbidden') return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'inline', message: uncertain, reload: true };
  if (cause.kind === 'unknownOutcome' || cause.kind === 'network')
    return { kind: 'inline', message: RETURN_UNKNOWN, reload: true };
  if (cause.kind === 'rateLimited')
    return { kind: 'inline', message: RETURN_RATE_LIMITED, reload: false };
  const field = cause.code ? FIELD_CODES[cause.code] : undefined;
  if (field) return { kind: 'field', ...field };
  const known = cause.code ? KNOWN[cause.code] : undefined;
  if (known) return { kind: 'inline', ...known };
  return { kind: 'inline', message: uncertain, reload: true };
}

export type ReturnFieldKey = ApprovalField | 'reason';

/** 실패 뒤 화면 상태 — 컴포넌트는 이 값대로만 바꾼다 */
export type FailureTransition = {
  /** 확인 단계를 닫는가 */
  closeConfirm: boolean;
  /** 확인할 때 저장한 본문 · 버전을 남기는가(닫지 않을 때만 남긴다) */
  keepSnapshot: boolean;
  fieldErrors: Partial<Record<ReturnFieldKey, string>>;
  /** 확인 단계 전체 오류(쓰기 401) */
  formError: string | null;
  notice: { tone: 'error'; message: string } | null;
  denied: 'forbidden' | null;
  /** 상세를 다시 읽는가 */
  reload: boolean;
  /** 옮길 포커스(`data-focus` 키) */
  focus: string;
};

const NO_TRANSITION = {
  closeConfirm: true,
  keepSnapshot: false,
  fieldErrors: {},
  formError: null,
  notice: null,
  denied: null,
  reload: false,
} satisfies Omit<FailureTransition, 'focus'>;

/**
 * 실패 분류 → 화면 상태 전이.
 * - 쓰기 401: 확인 단계 · 입력 · 스냅숏을 그대로 두고 확인 단계 전체 오류로 보인다(다시 로그인한 뒤 같은 확인으로 다시 누른다).
 * - 사유 칸 오류: 거절 확인 단계를 열어 둔 채 사유 칸에 붙인다.
 * - 승인 칸 오류: 확인 단계를 닫고 그 칸에 붙인다 — 고친 뒤 새 금액으로 다시 확인하게 한다.
 * - 403 · 결과 문구: 확인 단계를 닫는다. 결과 문구는 `reload` 면 상세를 다시 읽는다.
 */
export function failureTransition(failure: ReturnFailure, action: ReturnAction): FailureTransition {
  switch (failure.kind) {
    case 'form':
      return {
        ...NO_TRANSITION,
        closeConfirm: false,
        keepSnapshot: true,
        formError: failure.message,
        focus: 'return-form-error',
      };
    case 'field': {
      const fieldErrors: Partial<Record<ReturnFieldKey, string>> = {};
      fieldErrors[failure.field] = failure.message;
      return failure.field === 'reason'
        ? {
            ...NO_TRANSITION,
            closeConfirm: false,
            keepSnapshot: true,
            fieldErrors,
            focus: 'return-reason',
          }
        : { ...NO_TRANSITION, fieldErrors, focus: `return-${failure.field}` };
    }
    case 'access':
      return { ...NO_TRANSITION, denied: failure.denied, focus: `return-${action}` };
    case 'inline':
      return {
        ...NO_TRANSITION,
        notice: { tone: 'error', message: failure.message },
        reload: failure.reload,
        focus: 'return-notice',
      };
  }
}

/** 확인 단계에 들어갈 때 저장한 것 — 승인은 보낼 본문, 거절은 화면 버전 */
export type ConfirmSnapshot =
  | { action: 'approve'; request: ReturnApprovalRequest }
  | { action: 'reject'; expectedVersion: number };

/**
 * 열린 확인 단계가 지금 상세와 아직 맞는가. 확인 단계 동안 상세를 다시 읽어 버전이 바뀌었거나, 승인이라면 고른 귀책의 정책 환불액이 바뀌어
 * 저장한 환불 예정액과 달라졌거나, 버튼이 사라졌으면(`returnPanelMode`) false — 화면은 확인 단계를 닫고 스냅숏을 비운다.
 */
export function confirmStillValid(
  claim: ClaimDetail,
  mode: ReturnPanelMode,
  snapshot: ConfirmSnapshot,
): boolean {
  if (snapshot.action === 'reject')
    return mode.reject && snapshot.expectedVersion === claim.recovery.version;
  const { request } = snapshot;
  if (!mode.approve || request.expectedVersion !== claim.recovery.version) return false;
  const policy = claim.returnDecision ? policyRefundOf(claim.returnDecision, request.fault) : null;
  if (!policy || policy.currency !== request.deductionAmount.currency) return false;
  const refund = expectedRefundOf(policy, request.deductionAmount);
  return (
    refund.amount === request.expectedRefundAmount.amount &&
    refund.currency === request.expectedRefundAmount.currency
  );
}

/** 차감액 칸 이름에 붙이는 단위 — 통화 코드 대신 운영자 말로 */
export const deductionUnitLabel = (currency: Money['currency']) =>
  currency === 'KRW' ? '원' : '달러, 센트까지';
