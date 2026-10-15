export type FieldErrorProps = {
  id?: string;
  messages: readonly string[];
};

/** 필드 단위 검증 오류(fieldErrors) 표시. 메시지가 없으면 그리지 않는다. */
export function FieldError({ id, messages }: FieldErrorProps) {
  if (messages.length === 0) return null;
  return (
    <ul id={id} role="alert" className="mt-1 space-y-0.5 text-sm text-danger">
      {messages.map((message, index) => (
        <li key={`${index}-${message}`}>{message}</li>
      ))}
    </ul>
  );
}
