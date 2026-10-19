import { cx } from '@cf/ui';
import { optionValueState, type OptionSelection } from '@/lib/catalog/option-selection';
import type { ProductOptionGroup, ProductSku } from '@/lib/catalog/types';

/**
 * 옵션 그룹마다 직각 라디오 버튼 한 줄. 구매 패널과 Shop the Look 이 함께 쓴다.
 * 같은 페이지에 같은 상품의 옵션이 두 번 나올 수 있어 라디오 name 은 namePrefix 로 구분한다
 * (form 밖 라디오는 name 이 같으면 문서 전체에서 한 묶음이 된다).
 */
export function OptionGroupsField({
  groups,
  skus,
  selection,
  namePrefix,
  onSelect,
  size = 'md',
}: {
  groups: ProductOptionGroup[];
  skus: ProductSku[];
  selection: OptionSelection;
  namePrefix: string;
  onSelect: (groupId: string, valueId: string) => void;
  size?: 'md' | 'sm';
}) {
  return groups.map((group) => (
    <fieldset key={group.optionGroupId} className="flex flex-col gap-3">
      <legend
        className={cx('text-xs tracking-[0.15em] uppercase', size === 'md' ? 'mb-3' : 'mb-2')}
      >
        {group.name}
      </legend>
      <div className="flex flex-wrap gap-2">
        {group.values.map((value) => {
          // 어떤 조합으로도 살 수 없는 값만 막는다. 지금 고른 다른 옵션과 안 맞는 값은 취소선으로 보이되
          // 누를 수 있다 — 누르면 selectOption() 이 충돌하는 다른 선택을 푼다.
          const disabled =
            optionValueState(skus, {}, group.optionGroupId, value.optionValueId) !== 'available';
          const state = optionValueState(skus, selection, group.optionGroupId, value.optionValueId);
          const checked = selection[group.optionGroupId] === value.optionValueId;
          return (
            <label
              key={value.optionValueId}
              className={cx(
                'relative inline-flex cursor-pointer items-center justify-center border transition',
                size === 'md' ? 'min-h-10 min-w-12 px-4 text-sm' : 'min-h-9 min-w-10 px-3 text-xs',
                'has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-primary',
                checked ? 'border-fg bg-fg text-bg' : 'border-border hover:border-fg',
                !checked && state !== 'available' && 'text-muted-fg line-through',
                disabled && 'cursor-not-allowed hover:border-border',
              )}
            >
              <input
                type="radio"
                name={`${namePrefix}${group.optionGroupId}`}
                value={value.optionValueId}
                checked={checked}
                disabled={disabled}
                onChange={() => onSelect(group.optionGroupId, value.optionValueId)}
                className="sr-only"
              />
              {value.name}
              {state === 'soldOut' && <span className="sr-only"> (품절)</span>}
              {state === 'unavailable' && <span className="sr-only"> (선택 불가)</span>}
            </label>
          );
        })}
      </div>
    </fieldset>
  ));
}
