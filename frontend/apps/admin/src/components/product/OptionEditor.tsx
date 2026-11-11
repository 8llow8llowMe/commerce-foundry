'use client';

import { Button, FieldError, TextField } from '@cf/ui';
import { useId, useState } from 'react';
import {
  addOptionValue,
  GROUP_NAME_MAX,
  GROUPS_MAX,
  SKUS_MAX,
  combinationCount,
  type OptionGroupForm,
} from '@/lib/product/create';

/**
 * 옵션 그룹 편집 — 그룹 이름과 값(칩) 목록. 순서는 입력 순서가 노출 순서다.
 * 값을 더할 때 조합(SKU) 수가 상한을 넘으면 막는다. 그룹이 바뀌면 부모가 SKU 표를 조합으로 다시 만든다.
 */
export function OptionEditor({
  groups,
  errorsOf,
  disabled,
  onChange,
}: {
  groups: OptionGroupForm[];
  errorsOf: (key: string) => string[];
  disabled: boolean;
  onChange: (groups: OptionGroupForm[]) => void;
}) {
  const count = combinationCount(groups);
  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted-fg">
        옵션이 없으면 비워 두세요 — SKU 한 개(기본)로 등록됩니다. 옵션 그룹은 {GROUPS_MAX}개,
        조합(SKU)은 {SKUS_MAX}개까지입니다. 지금 조합 {count}개. 영문 이름은 등록한 뒤 옵션 · SKU
        수정에서 넣습니다.
      </p>
      {groups.map((group, index) => (
        <OptionGroupRow
          key={group.id}
          group={group}
          index={index}
          groups={groups}
          errorsOf={errorsOf}
          disabled={disabled}
          onChange={onChange}
        />
      ))}
      <FieldError messages={errorsOf('optionGroups')} />
      <Button
        type="button"
        variant="secondary"
        className="self-start"
        disabled={disabled || groups.length >= GROUPS_MAX}
        onClick={() => onChange([...groups, { id: crypto.randomUUID(), name: '', values: [] }])}
      >
        옵션 그룹 추가
      </Button>
    </div>
  );
}

function OptionGroupRow({
  group,
  index,
  groups,
  errorsOf,
  disabled,
  onChange,
}: {
  group: OptionGroupForm;
  index: number;
  groups: OptionGroupForm[];
  errorsOf: (key: string) => string[];
  disabled: boolean;
  onChange: (groups: OptionGroupForm[]) => void;
}) {
  const id = useId();
  const [draft, setDraft] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const replace = (next: OptionGroupForm) =>
    onChange(groups.map((item) => (item.id === group.id ? next : item)));

  const add = () => {
    const result = addOptionValue(groups, group.id, draft);
    if (!result.ok) {
      setMessage(result.message);
      return;
    }
    setMessage(null);
    setDraft('');
    onChange(result.groups);
  };

  const valueErrors = [...(message ? [message] : []), ...errorsOf(`optionGroups.${index}.values`)];

  return (
    <fieldset className="flex flex-col gap-3 rounded-md border border-border p-4">
      <legend className="px-1 text-sm font-medium">옵션 {index + 1}</legend>
      <div className="flex flex-wrap items-end gap-3">
        <TextField
          label={`옵션 이름 (${GROUP_NAME_MAX}자 이하)`}
          value={group.name}
          maxLength={GROUP_NAME_MAX}
          placeholder="예: 사이즈"
          onChange={(event) => replace({ ...group, name: event.currentTarget.value })}
          errors={errorsOf(`optionGroups.${index}.name`)}
          className="w-56"
        />
        <Button
          type="button"
          variant="secondary"
          disabled={disabled}
          onClick={() => onChange(groups.filter((item) => item.id !== group.id))}
        >
          이 옵션 삭제
        </Button>
      </div>
      <div className="flex flex-col gap-2">
        <span id={`${id}-values`} className="text-sm font-medium">
          옵션 값
        </span>
        {group.values.length > 0 ? (
          <ul aria-labelledby={`${id}-values`} className="flex flex-wrap gap-2">
            {group.values.map((value) => (
              <li
                key={value}
                className="flex items-center gap-1 rounded-full bg-muted py-1 pr-1 pl-3 text-sm"
              >
                {value}
                <button
                  type="button"
                  disabled={disabled}
                  aria-label={`${value} 값 삭제`}
                  className="rounded-full px-2 text-muted-fg hover:text-fg"
                  onClick={() =>
                    replace({ ...group, values: group.values.filter((item) => item !== value) })
                  }
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-fg">아직 값이 없습니다.</p>
        )}
        <div className="flex items-end gap-2">
          <label htmlFor={`${id}-draft`} className="sr-only">
            옵션 {index + 1} 값 입력
          </label>
          <input
            id={`${id}-draft`}
            value={draft}
            placeholder="예: M — 입력 후 Enter"
            aria-invalid={valueErrors.length > 0 || undefined}
            aria-describedby={valueErrors.length > 0 ? `${id}-values-error` : undefined}
            onChange={(event) => {
              setDraft(event.currentTarget.value);
              setMessage(null);
            }}
            onKeyDown={(event) => {
              // 폼 제출이 아니라 값 추가다
              if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
                event.preventDefault();
                add();
              }
            }}
            className="h-10 w-56 rounded-md border border-border bg-bg px-3 text-fg outline-none focus:ring-2 focus:ring-primary"
          />
          <Button type="button" variant="secondary" disabled={disabled} onClick={add}>
            값 추가
          </Button>
        </div>
        <FieldError id={`${id}-values-error`} messages={valueErrors} />
      </div>
    </fieldset>
  );
}
