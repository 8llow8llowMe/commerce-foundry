'use client';

import { Checkbox } from '@cf/ui';
import type { ChangeEvent } from 'react';
import { useDictionary } from '@/components/i18n/LocaleProvider';
import type { SignUpAgreements } from '@/lib/auth/agreements';

export type AgreementErrors = {
  agreements: string[];
  termsOfService: string[];
  privacyPolicy: string[];
  marketing: string[];
};

/**
 * 가입 약관 동의 칸(전체 동의 + 필수 2 · 선택 1). 일반 가입 폼과 소셜 신규 가입 동의 화면이 같은 항목 · 문구를 쓴다.
 * 필수 칸은 `required` 라 폼 제출을 브라우저가 막는다.
 */
export function AgreementsFieldset({
  value,
  onChange,
  errors,
}: {
  value: SignUpAgreements;
  onChange: (next: SignUpAgreements) => void;
  errors: AgreementErrors;
}) {
  const t = useDictionary().auth.signUp;
  const allAgreed = value.termsOfService && value.privacyPolicy && value.marketing;
  const toggle = (key: keyof SignUpAgreements) => (event: ChangeEvent<HTMLInputElement>) =>
    onChange({ ...value, [key]: event.currentTarget.checked });

  return (
    <fieldset className="mt-3 flex flex-col gap-3">
      <legend className="sr-only">{t.agreements}</legend>
      <Checkbox
        label={<span className="font-medium">{t.agreeAll}</span>}
        checked={allAgreed}
        onChange={(event) => {
          const checked = event.currentTarget.checked;
          onChange({ termsOfService: checked, privacyPolicy: checked, marketing: checked });
        }}
        errors={errors.agreements}
      />
      <div className="flex flex-col gap-3 pl-7">
        <Checkbox
          label={t.termsOfService}
          name="termsOfService"
          checked={value.termsOfService}
          onChange={toggle('termsOfService')}
          required
          errors={errors.termsOfService}
        />
        <Checkbox
          label={t.privacyPolicy}
          name="privacyPolicy"
          checked={value.privacyPolicy}
          onChange={toggle('privacyPolicy')}
          required
          errors={errors.privacyPolicy}
        />
        <Checkbox
          label={t.marketing}
          name="marketing"
          checked={value.marketing}
          onChange={toggle('marketing')}
          errors={errors.marketing}
        />
      </div>
    </fieldset>
  );
}
