'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button, Checkbox, FieldError, TextField } from '@cf/ui';
import { useCallback, useEffect, useId, useState, type FormEvent } from 'react';
import { useDictionary } from '@/components/i18n/LocaleProvider';
import {
  NO_PROFILE_ERRORS,
  profileDraft,
  profileErrors,
  profilePatch,
  type ProfileDraft,
  type ProfileErrors,
} from '@/lib/account/profile-form';
import type { MemberProfile } from '@/lib/account/types';
import { SignedOutNotice } from './SignedOutNotice';

const PROFILE_PATH = '/api/v1/members/me';

type Load = { status: 'loading' } | { status: 'failed' } | { status: 'signedOut' };

/**
 * 회원 정보 수정(#28). `GET /api/v1/members/me` 로 읽고, 바뀐 칸만 `PATCH` 한다(바뀐 것이 없으면 보내지 않는다 — 보내면 `AUTH_115`).
 * 이메일은 바꿀 수 없다. 동시 수정(409 `AUTH_004`)이면 다시 읽어 와 사용자가 확인 후 다시 저장하게 한다.
 */
export function ProfileForm() {
  const t = useDictionary().account;
  const [profile, setProfile] = useState<MemberProfile | Load>({ status: 'loading' });
  const [draft, setDraft] = useState<ProfileDraft | null>(null);
  const [errors, setErrors] = useState<ProfileErrors>(NO_PROFILE_ERRORS);
  const [notice, setNotice] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const phoneNoteId = useId();

  const show = useCallback((loaded: MemberProfile) => {
    setProfile(loaded);
    setDraft(profileDraft(loaded));
  }, []);

  const load = useCallback(
    () =>
      apiFetch<MemberProfile>(PROFILE_PATH).then(show, (cause: unknown) => {
        setProfile({
          status:
            cause instanceof ApiError && cause.kind === 'unauthorized' ? 'signedOut' : 'failed',
        });
      }),
    [show],
  );

  useEffect(() => {
    void load();
  }, [load]);

  if ('status' in profile) {
    if (profile.status === 'signedOut') return <SignedOutNotice returnPath="/mypage/profile" />;
    if (profile.status === 'failed') {
      return (
        <div className="flex flex-col items-center gap-4 py-16 text-center">
          <p role="alert" className="text-sm text-muted-fg">
            {t.loadFailed}
          </p>
          <Button
            variant="secondary"
            onClick={() => {
              setProfile({ status: 'loading' });
              void load();
            }}
          >
            {t.reload}
          </Button>
        </div>
      );
    }
    return (
      <p role="status" className="py-16 text-center text-sm text-muted-fg">
        {t.loading}
      </p>
    );
  }
  if (!draft) return null;

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (saving) return;
    setErrors(NO_PROFILE_ERRORS);
    const patch = profilePatch(profile, draft);
    if (!patch) {
      setNotice(t.profile.unchanged);
      return;
    }
    setSaving(true);
    setNotice(null);
    try {
      show(await apiFetch<MemberProfile>(PROFILE_PATH, { method: 'PATCH', json: patch }));
      setNotice(t.profile.saved);
    } catch (cause) {
      if (cause instanceof ApiError && cause.kind === 'unauthorized') {
        setProfile({ status: 'signedOut' });
      } else if (cause instanceof ApiError && cause.code === 'AUTH_004') {
        await load();
        setErrors({ ...NO_PROFILE_ERRORS, form: [t.profile.conflict] });
      } else {
        setErrors(profileErrors(cause, t));
      }
    } finally {
      setSaving(false);
    }
  };

  const update = (patch: Partial<ProfileDraft>) => {
    setDraft({ ...draft, ...patch });
    setNotice(null);
  };

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-5">
      <div className="flex flex-col gap-1">
        <TextField label={t.profile.email} value={profile.email} readOnly disabled />
        <p className="text-sm text-muted-fg">{t.profile.emailNote}</p>
      </div>
      <TextField
        label={t.profile.name}
        name="name"
        autoComplete="name"
        maxLength={50}
        required
        value={draft.name}
        onChange={(event) => update({ name: event.currentTarget.value })}
        errors={errors.name}
      />
      <div className="flex flex-col gap-1">
        <TextField
          label={t.profile.phone}
          name="phone"
          type="tel"
          autoComplete="tel"
          inputMode="tel"
          maxLength={13}
          value={draft.phone}
          onChange={(event) => update({ phone: event.currentTarget.value })}
          // 안내는 오류가 없을 때만 — 오류가 있으면 TextField 가 오류 문구를 연결한다
          aria-describedby={errors.phone.length === 0 ? phoneNoteId : undefined}
          errors={errors.phone}
        />
        {errors.phone.length === 0 && (
          <p id={phoneNoteId} className="text-sm text-muted-fg">
            {t.profile.phoneNote}
          </p>
        )}
      </div>
      <Checkbox
        label={t.profile.marketing}
        name="marketingAgreed"
        checked={draft.marketingAgreed}
        onChange={(event) => update({ marketingAgreed: event.currentTarget.checked })}
        errors={errors.marketingAgreed}
        className="pt-2"
      />

      <FieldError messages={errors.form} />
      {notice && (
        <p role="status" className="text-sm text-muted-fg">
          {notice}
        </p>
      )}
      <Button type="submit" disabled={saving} className="h-12 w-full tracking-[0.15em]">
        {saving ? t.profile.saving : t.profile.save}
      </Button>
    </form>
  );
}
