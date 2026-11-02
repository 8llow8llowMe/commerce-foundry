'use client';

import { apiFetch } from '@cf/api-client';
import { Button, Checkbox, FieldError, TextField } from '@cf/ui';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { normalizeOtpCode, OTP_CHECK_INPUT, otpRateLimitText } from '@/lib/admin-account/otp';
import {
  PEER_UNCERTAIN_FAILURE,
  peerOtpRecoveryPath,
  peerRecoveryFailure,
  peerSubmitProblem,
  recoveredCodes,
} from '@/lib/admin-account/peer-otp';
import type { AccessDenied, FormErrors } from '@/lib/api-errors';
import type { MemberListItem } from '@/lib/member/query';
import type { Cooldown } from './OtpVerifyForm';

/**
 * 동료 관리자 OTP 복구(#428 · BE #238 PR-C) — 대상과 무효화되는 것을 보여 주고, 내 현재 비밀번호 + 코드로 재인증해 요청한다.
 * 칸은 제어하지 않는다 — 비밀번호 · 코드를 React 상태 · 주소 · 로그에 두지 않고 제출할 때만 `FormData` 로 읽는다.
 * 새 복구 코드는 `onRecovered` 로 부모 화면 상태에만 올린다. 429 는 부모가 가진 쿨다운(행위자별 시도 창)으로 버튼을 막는다.
 */
export function PeerOtpRecoveryForm({
  target,
  cooldown,
  onRecovered,
  onGone,
  onDenied,
  onCancel,
}: {
  target: Pick<MemberListItem, 'memberId' | 'name' | 'email'>;
  cooldown: Cooldown;
  onRecovered: (codes: string[]) => void;
  /** 대상이 복구할 수 없는 계정이다(`AUTH_033`) — 폼을 접고 줄에 문구를 둔다 */
  onGone: (message: string) => void;
  onDenied: (denied: AccessDenied) => void;
  onCancel: () => void;
}) {
  const [submitting, setSubmitting] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);
  const [errors, setErrors] = useState<FormErrors>({});
  const formRef = useRef<HTMLFormElement>(null);

  useEffect(() => {
    formRef.current?.querySelector<HTMLElement>('[data-focus="peer-heading"]')?.focus();
  }, []);

  const waiting = cooldown.remainingSec > 0;

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting || waiting) return;
    const formElement = event.currentTarget;
    const data = new FormData(formElement);
    const currentPassword = String(data.get('currentPassword') ?? '');
    const code = normalizeOtpCode(String(data.get('code') ?? ''));
    const problem = peerSubmitProblem(acknowledged, code);
    if (problem) {
      setErrors(problem);
      return;
    }
    const clearCode = () => {
      // 보낸 코드가 이미 쓰였을 수 있다 — 같은 코드로 다시 보내지 않게 비운다(비밀번호는 남긴다)
      const codeInput = formElement.querySelector<HTMLInputElement>('input[name="code"]');
      if (codeInput) codeInput.value = '';
    };
    setSubmitting(true);
    setErrors({});
    try {
      const result = await apiFetch<unknown>(peerOtpRecoveryPath(target.memberId), {
        method: 'POST',
        json: { currentPassword, code },
      });
      setSubmitting(false);
      const codes = recoveredCodes(result);
      if (codes) {
        onRecovered(codes);
      } else {
        clearCode();
        setErrors(PEER_UNCERTAIN_FAILURE.errors);
      }
    } catch (cause) {
      setSubmitting(false);
      const failure = peerRecoveryFailure(cause);
      if (failure.kind === 'rateLimited') {
        cooldown.startFrom(cause);
        return;
      }
      if (failure.kind === 'access') {
        onDenied(failure.denied);
        return;
      }
      if (failure.gone) {
        onGone(failure.errors.form?.[0] ?? OTP_CHECK_INPUT);
        return;
      }
      if (failure.codeSpent) clearCode();
      setErrors(failure.errors);
    }
  };

  const fieldOnly =
    !errors.form?.length &&
    Object.entries(errors).some(([key, list]) => key !== 'acknowledge' && list.length > 0);
  const formMessages = waiting
    ? [otpRateLimitText(cooldown.remainingSec)]
    : (errors.form ?? (fieldOnly ? [OTP_CHECK_INPUT] : []));

  return (
    <form
      ref={formRef}
      onSubmit={onSubmit}
      aria-labelledby="peer-otp-heading"
      className="flex max-w-xl flex-col gap-5 rounded-md border border-border p-5"
    >
      <div className="flex flex-col gap-2">
        <h2
          id="peer-otp-heading"
          data-focus="peer-heading"
          tabIndex={-1}
          className="text-lg font-semibold outline-none"
        >
          2단계 인증 복구 — {target.name}
        </h2>
        <p className="text-sm text-muted-fg">
          대상 계정: {target.name} · {target.email}
        </p>
        <p className="text-sm">
          휴대폰을 잃어버리고 복구 코드도 쓸 수 없는 관리자를 대신해 2단계 인증을 새로 바꿉니다.
          대상 관리자 본인의 요청인지 먼저 별도로 확인해 주세요.
        </p>
      </div>
      <div role="note" className="flex flex-col gap-2 rounded-md bg-muted px-4 py-3 text-sm">
        <p className="font-medium">복구하면 바로 다음이 일어납니다.</p>
        <ul className="list-disc space-y-1 pl-5">
          <li>이 관리자의 기존 인증 앱 등록과 복구 코드가 모두 무효화돼 더는 쓸 수 없습니다.</li>
          <li>이 관리자의 지금 로그인이 끊깁니다.</li>
          <li>새 복구 코드 8개를 이 화면에 한 번만 보여 드립니다. 다시 볼 수 없습니다.</li>
        </ul>
      </div>
      <fieldset disabled={submitting} className="flex flex-col gap-5">
        <Checkbox
          label={`${target.name} 님의 기존 2단계 인증과 복구 코드가 무효화되는 것을 확인했습니다.`}
          checked={acknowledged}
          onChange={(event) => {
            setAcknowledged(event.currentTarget.checked);
            setErrors((prev) => {
              const next = { ...prev };
              delete next.acknowledge;
              return next;
            });
          }}
          errors={errors.acknowledge}
        />
        <p className="text-sm text-muted-fg">
          본인 확인을 위해 <strong className="font-medium text-fg">내</strong> 비밀번호와 내 인증
          코드를 입력합니다.
        </p>
        <TextField
          label="내 현재 비밀번호"
          name="currentPassword"
          type="password"
          autoComplete="current-password"
          required
          errors={errors.currentPassword}
        />
        <div className="flex flex-col gap-1">
          <TextField
            label="내 인증 코드 또는 복구 코드"
            name="code"
            autoComplete="one-time-code"
            spellCheck={false}
            maxLength={40}
            required
            errors={errors.code}
          />
          <p className="text-xs text-muted-fg">
            내 인증 앱의 숫자 6자리를 입력합니다. 휴대폰을 쓸 수 없으면 내 복구 코드 하나를
            입력합니다.
          </p>
        </div>
      </fieldset>
      <FieldError messages={formMessages} />
      <div className="flex flex-wrap gap-3">
        <Button type="submit" disabled={submitting || waiting}>
          {submitting ? '복구하는 중…' : '2단계 인증 복구'}
        </Button>
        <Button variant="secondary" onClick={onCancel} disabled={submitting}>
          취소
        </Button>
      </div>
    </form>
  );
}
