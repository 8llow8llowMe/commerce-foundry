import { useId, type InputHTMLAttributes } from 'react';
import { cx } from './cx';
import { FieldError } from './FieldError';

export type TextFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> & {
  label: string;
  errors?: readonly string[];
};

export function TextField({ label, errors = [], className, ...rest }: TextFieldProps) {
  const id = useId();
  const errorId = `${id}-error`;
  const invalid = errors.length > 0;

  return (
    <div className={cx('flex flex-col gap-1', className)}>
      <label htmlFor={id} className="text-sm font-medium text-fg">
        {label}
      </label>
      <input
        id={id}
        aria-invalid={invalid || undefined}
        aria-describedby={invalid ? errorId : undefined}
        className={cx(
          'h-10 rounded-md border bg-bg px-3 text-fg outline-none focus:ring-2 focus:ring-primary',
          invalid ? 'border-danger' : 'border-border',
        )}
        {...rest}
      />
      <FieldError id={errorId} messages={errors} />
    </div>
  );
}
