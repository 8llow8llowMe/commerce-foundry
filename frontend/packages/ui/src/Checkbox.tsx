import { useId, type InputHTMLAttributes, type ReactNode } from 'react';
import { cx } from './cx';
import { FieldError } from './FieldError';

export type CheckboxProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'id' | 'type'> & {
  label: ReactNode;
  errors?: readonly string[];
};

/** 체크박스 한 칸. 글자를 눌러도 체크되게 label 로 감싸고, 오류는 TextField 와 같은 방식으로 연결한다 */
export function Checkbox({ label, errors = [], className, ...rest }: CheckboxProps) {
  const id = useId();
  const errorId = `${id}-error`;
  const invalid = errors.length > 0;

  return (
    <div className={cx('flex flex-col', className)}>
      <label htmlFor={id} className="flex cursor-pointer items-start gap-3 text-sm text-fg">
        <input
          id={id}
          type="checkbox"
          aria-invalid={invalid || undefined}
          aria-describedby={invalid ? errorId : undefined}
          className="mt-0.5 size-4 shrink-0 cursor-pointer accent-primary"
          {...rest}
        />
        <span>{label}</span>
      </label>
      <FieldError id={errorId} messages={errors} />
    </div>
  );
}
