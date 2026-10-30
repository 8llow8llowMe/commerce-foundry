'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  normalizeOtpCode,
  OTP_CHECK_INPUT,
  OTP_DEACTIVATION_PATH,
  OTP_PATH,
  OTP_RECOVERY_CODES_PATH,
  otpFailure,
  otpRateLimitText,
  reauthCodeProblem,
  totpProblem,
  type OtpFailure,
  type RecoveryCodes,
} from '@/lib/admin-account/otp';
import type { FormErrors } from '@/lib/api-errors';

export type VerifyAction = 'confirm' | 'deactivate' | 'regenerate';

const PATH: Record<VerifyAction, string> = {
  confirm: OTP_PATH,
  deactivate: OTP_DEACTIVATION_PATH,
  regenerate: OTP_RECOVERY_CODES_PATH,
};

const SUBMIT: Record<VerifyAction, { idle: string; busy: string }> = {
  confirm: { idle: '등록 완료', busy: '확인하는 중…' },
  deactivate: { idle: '2단계 인증 끄기', busy: '끄는 중…' },
  regenerate: { idle: '새 복구 코드 받기', busy: '받는 중…' },
};

export type Cooldown = { remainingSec: number; startFrom: (error: unknown) => boolean };

/**
 * 현재 비밀번호 + 코드 재인증 폼(#417) — 등록 확인 · 해제 · 복구 코드 재발급이 같이 쓴다.
 * 칸은 제어하지 않는다 — 비밀번호 · 코드를 React 상태 · 주소 · 로그에 두지 않고 제출할 때만 `FormData` 로 읽는다.
 * 429 는 부모가 가진 쿨다운(세 요청이 같은 시도 창을 쓴다)으로 버튼을 막는다. 지금 단계를 접는 실패는 `onLeave` 로 올린다.
 */
export function OtpVerifyForm({
  action,
  cooldown,
  onSuccess,
  onLeave,
  onCancel,
}: {
  action: VerifyAction;
  cooldown: Cooldown;
  /** 해제는 null, 확인 · 재발급은 새 복구 코드 */
  onSuccess: (codes: string[] | null) => void;
  onLeave: (failure: Exclude<OtpFailure, { kind: 'rateLimited' }>) => void;
  onCancel: () => void;
}) {
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<FormErrors>({});
  const formRef = useRef<HTMLFormElement>(null);

  useEffect(() => {
    formRef.current?.querySelector<HTMLInputElement>('input[name="currentPassword"]')?.focus();
  }, []);

  const confirm = action === 'confirm';
  const waiting = cooldown.remainingSec > 0;

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting || waiting) return;
    const data = new FormData(event.currentTarget);
    const currentPassword = String(data.get('currentPassword') ?? '');
    const code = normalizeOtpCode(String(data.get('code') ?? ''));
    const problem = confirm ? totpProblem(code) : reauthCodeProblem(code);
    if (problem) {
      setErrors({ code: [problem] });
      return;
    }
    setSubmitting(true);
    setErrors({});
    try {
      const result = await apiFetch<RecoveryCodes | null>(PATH[action], {
        method: 'POST',
        json: { currentPassword, code },
      });
      setSubmitting(false);
      onSuccess(result?.recoveryCodes ?? null);
    } catch (cause) {
      setSubmitting(false);
      const failure = otpFailure(cause, action);
      if (failure.kind === 'rateLimited') {
        cooldown.startFrom(cause);
        return;
      }
      if (failure.kind === 'access' || failure.next) {
        onLeave(failure);
        return;
      }
      setErrors(failure.errors);
    }
  };

  const fieldOnly = !errors.form?.length && Object.values(errors).some((list) => list.length > 0);
  const formMessages = waiting
    ? [otpRateLimitText(cooldown.remainingSec)]
    : (errors.form ?? (fieldOnly ? [OTP_CHECK_INPUT] : []));

  return (
    <form
      ref={formRef}
      onSubmit={onSubmit}
      aria-label={SUBMIT[action].idle}
      className="flex max-w-md flex-col gap-5"
    >
      <fieldset disabled={submitting} className="flex flex-col gap-5">
        <TextField
          label="현재 비밀번호"
          name="currentPassword"
          type="password"
          autoComplete="current-password"
          required
          errors={errors.currentPassword}
        />
        <div className="flex flex-col gap-1">
          <TextField
            label={confirm ? '인증 코드' : '인증 코드 또는 복구 코드'}
            name="code"
            autoComplete="one-time-code"
            inputMode={confirm ? 'numeric' : 'text'}
            spellCheck={false}
            maxLength={confirm ? 7 : 40}
            required
            errors={errors.code}
          />
          <p className="text-xs text-muted-fg">
            {confirm
              ? '인증 앱에 보이는 숫자 6자리를 입력합니다.'
              : '인증 앱의 숫자 6자리를 입력합니다. 휴대폰을 쓸 수 없으면 복구 코드 하나를 입력합니다.'}
          </p>
        </div>
      </fieldset>
      <FieldError messages={formMessages} />
      <div className="flex flex-wrap gap-3">
        <Button type="submit" disabled={submitting || waiting}>
          {submitting ? SUBMIT[action].busy : SUBMIT[action].idle}
        </Button>
        <Button variant="secondary" onClick={onCancel} disabled={submitting}>
          취소
        </Button>
      </div>
    </form>
  );
}
