'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import { useEffect, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import {
  formatOtpDate,
  formatOtpDeadline,
  formatOtpSecret,
  LOW_RECOVERY_CODES,
  OTP_ENROLLMENT_PATH,
  OTP_PATH,
  OTP_TOO_MANY,
  otpFailure,
  type OtpEnrollment,
  type OtpFailure,
  type OtpStatus,
} from '@/lib/admin-account/otp';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { useLoginCooldown } from '@/lib/auth/use-login-cooldown';
import { OtpQrCode } from './OtpQrCode';
import { OtpVerifyForm } from './OtpVerifyForm';
import { RecoveryCodesPanel } from './RecoveryCodesPanel';

type Loaded =
  | { key: number; kind: 'ok'; status: OtpStatus }
  | { key: number; kind: 'failed' }
  | { key: number; kind: 'denied'; denied: AccessDenied };

/** 지금 단계. 비밀 키(`enrollment`) · 복구 코드(`codes`)는 여기(화면 상태)에만 있고 단계를 떠나면 버린다 */
type View =
  | { kind: 'overview' }
  | { kind: 'enrolling'; enrollment: OtpEnrollment }
  | { kind: 'reauth'; action: 'deactivate' | 'regenerate' }
  | { kind: 'codes'; codes: string[]; regenerated: boolean };

/**
 * 내 2단계 인증(#417, BE #238 PR-A2) — 상태 보기 · 등록(시작 → QR · 직접 입력 키 → 6자리 확인 → 복구 코드 한 번) ·
 * 해제 · 복구 코드 다시 받기. 확인 · 해제 · 재발급은 현재 비밀번호 + 코드 재인증이고, 셋이 같은 시도 창(15분 5회)을 써서
 * 429 쿨다운을 하나로 둔다.
 */
export function OtpSettings() {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reloads, setReloads] = useState(0);
  const [view, setView] = useState<View>({ kind: 'overview' });
  const [notice, setNotice] = useState<string | null>(null);
  const [startError, setStartError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [denied, setDenied] = useState<AccessDenied | null>(null);
  const cooldown = useLoginCooldown();

  useEffect(() => {
    let alive = true;
    const key = reloads;
    apiFetch<OtpStatus>(OTP_PATH).then(
      (status) => alive && setLoaded({ key, kind: 'ok', status }),
      (cause: unknown) => {
        if (!alive) return;
        const access = accessDeniedOf(cause);
        setLoaded(access ? { key, kind: 'denied', denied: access } : { key, kind: 'failed' });
      },
    );
    return () => {
      alive = false;
    };
  }, [reloads]);

  const reload = () => setReloads((n) => n + 1);
  const toOverview = (message: string | null) => {
    setView({ kind: 'overview' });
    setNotice(message);
    setStartError(null);
  };

  /** 지금 단계를 접는 실패 — 권한 안내, 또는 상태를 다시 읽고 첫 화면에 문구를 둔다 */
  const leave = (failure: Exclude<OtpFailure, { kind: 'rateLimited' }>) => {
    if (failure.kind === 'access') {
      setDenied(failure.denied);
      return;
    }
    toOverview(failure.errors.form?.[0] ?? null);
    reload();
  };

  const startEnrollment = async () => {
    if (starting) return;
    setStarting(true);
    setNotice(null);
    setStartError(null);
    try {
      const enrollment = await apiFetch<OtpEnrollment>(OTP_ENROLLMENT_PATH, { method: 'POST' });
      setView({ kind: 'enrolling', enrollment });
    } catch (cause) {
      const failure = otpFailure(cause, 'start');
      if (failure.kind === 'access') setDenied(failure.denied);
      else if (failure.kind === 'rateLimited') setStartError(OTP_TOO_MANY);
      else {
        setStartError(failure.errors.form?.[0] ?? null);
        if (failure.next) reload();
      }
    } finally {
      setStarting(false);
    }
  };

  if (denied) return <AccessNotice denied={denied} />;
  if (loaded?.kind === 'denied') return <AccessNotice denied={loaded.denied} />;

  if (view.kind === 'codes') {
    return (
      <RecoveryCodesPanel
        codes={view.codes}
        regenerated={view.regenerated}
        onClose={() => setView({ kind: 'overview' })}
      />
    );
  }

  if (view.kind === 'enrolling') {
    const { enrollment } = view;
    const deadline = formatOtpDeadline(enrollment.expiresAt);
    return (
      <section aria-labelledby="otp-enroll-heading" className="flex max-w-xl flex-col gap-6">
        <div>
          <h2 id="otp-enroll-heading" className="text-lg font-semibold">
            2단계 인증 등록
          </h2>
          <p className="mt-1 text-sm text-muted-fg">
            휴대폰 인증 앱에서 계정 추가를 누르고 아래 QR 코드를 찍습니다.
            {deadline && ` ${deadline}까지 등록을 마쳐 주세요.`}
          </p>
        </div>
        <div className="flex flex-wrap items-start gap-6">
          <OtpQrCode value={enrollment.otpauthUri} />
          <div className="flex max-w-xs flex-col gap-2 text-sm">
            <p className="text-muted-fg">
              QR 코드를 찍을 수 없으면 이 키를 인증 앱에 직접 입력합니다.
            </p>
            <p className="font-mono break-all select-all">{formatOtpSecret(enrollment.secret)}</p>
            <p className="text-xs text-muted-fg">시간 기준(TOTP) · 6자리 · 30초로 설정합니다.</p>
          </div>
        </div>
        <OtpVerifyForm
          action="confirm"
          cooldown={cooldown}
          onSuccess={(codes) => {
            setView({ kind: 'codes', codes: codes ?? [], regenerated: false });
            setNotice('2단계 인증을 켰습니다. 다음 로그인부터 인증 코드를 입력합니다.');
            reload();
          }}
          onLeave={leave}
          onCancel={() => toOverview(null)}
        />
      </section>
    );
  }

  if (view.kind === 'reauth') {
    const off = view.action === 'deactivate';
    return (
      <section aria-labelledby="otp-reauth-heading" className="flex max-w-xl flex-col gap-4">
        <div>
          <h2 id="otp-reauth-heading" className="text-lg font-semibold">
            {off ? '2단계 인증 끄기' : '복구 코드 다시 받기'}
          </h2>
          <p className="mt-1 text-sm text-muted-fg">
            {off
              ? '끄면 비밀번호만으로 로그인하고, 지금 가진 복구 코드도 모두 쓸 수 없게 됩니다. 본인 확인을 위해 현재 비밀번호와 코드를 입력해 주세요.'
              : '새 복구 코드 8개를 받으면 이전 복구 코드는 모두 쓸 수 없게 됩니다. 본인 확인을 위해 현재 비밀번호와 코드를 입력해 주세요.'}
          </p>
        </div>
        <OtpVerifyForm
          action={view.action}
          cooldown={cooldown}
          onSuccess={(codes) => {
            if (off || !codes) {
              toOverview('2단계 인증을 껐습니다. 이제 비밀번호만으로 로그인합니다.');
            } else {
              setView({ kind: 'codes', codes, regenerated: true });
              setNotice('새 복구 코드를 받았습니다.');
            }
            reload();
          }}
          onLeave={leave}
          onCancel={() => toOverview(null)}
        />
      </section>
    );
  }

  const current = loaded?.key === reloads ? loaded : null;
  const status = loaded?.kind === 'ok' ? loaded.status : null;

  return (
    <section aria-label="2단계 인증 상태" className="flex max-w-xl flex-col gap-4">
      {notice && (
        <p role="status" className="rounded-md bg-muted px-4 py-3 text-sm">
          {notice}
        </p>
      )}
      {!status && !current && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {!status && current?.kind === 'failed' && (
        <div className="flex flex-col items-start gap-3">
          <FieldError messages={[LOAD_FAILED]} />
          <Button variant="secondary" onClick={reload}>
            다시 불러오기
          </Button>
        </div>
      )}
      {status && !status.enrolled && (
        <div className="flex flex-col items-start gap-3 rounded-md border border-border p-5">
          <p className="font-medium">사용하지 않음</p>
          <p className="text-sm text-muted-fg">
            켜면 로그인할 때 비밀번호 다음에 휴대폰 인증 앱의 6자리 코드를 한 번 더 입력합니다.
            비밀번호가 새어도 다른 사람이 어드민에 들어올 수 없습니다.
          </p>
          <Button onClick={startEnrollment} disabled={starting}>
            {starting ? '준비하는 중…' : '2단계 인증 켜기'}
          </Button>
          <FieldError messages={startError ? [startError] : []} />
        </div>
      )}
      {status?.enrolled && (
        <div className="flex flex-col items-start gap-3 rounded-md border border-border p-5">
          <p className="font-medium">사용 중</p>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            {formatOtpDate(status.confirmedAt) && (
              <>
                <dt className="text-muted-fg">켠 날</dt>
                <dd>{formatOtpDate(status.confirmedAt)}</dd>
              </>
            )}
            <dt className="text-muted-fg">남은 복구 코드</dt>
            <dd>{status.remainingRecoveryCodes}개</dd>
          </dl>
          {status.remainingRecoveryCodes <= LOW_RECOVERY_CODES && (
            <p className="text-sm text-danger">
              복구 코드가 얼마 남지 않았습니다. 새 복구 코드를 받아 보관해 주세요.
            </p>
          )}
          <div className="flex flex-wrap gap-3">
            <Button
              variant="secondary"
              onClick={() => {
                setNotice(null);
                setView({ kind: 'reauth', action: 'regenerate' });
              }}
            >
              복구 코드 다시 받기
            </Button>
            <Button
              variant="secondary"
              onClick={() => {
                setNotice(null);
                setView({ kind: 'reauth', action: 'deactivate' });
              }}
            >
              2단계 인증 끄기
            </Button>
          </div>
        </div>
      )}
    </section>
  );
}
