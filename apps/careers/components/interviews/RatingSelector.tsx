'use client';

interface Props {
  value: number | null;
  onChange: (v: number | null) => void;
  max?: number;
  label?: string;
  allowSkip?: boolean;
  disabled?: boolean;
}

export default function RatingSelector({
  value,
  onChange,
  max = 5,
  label,
  allowSkip = false,
  disabled = false,
}: Props) {
  const buttons = Array.from({ length: max }, (_, i) => i + 1);

  return (
    <div>
      {label && (
        <label className="mb-2 block text-sm font-medium text-gray-700">{label}</label>
      )}
      <div className="flex items-center gap-2">
        {buttons.map((n) => {
          const active = value === n;
          return (
            <button
              key={n}
              type="button"
              disabled={disabled}
              onClick={() => onChange(n)}
              className={
                'inline-flex h-10 w-10 items-center justify-center rounded-md border text-sm font-semibold transition-colors ' +
                (active
                  ? 'border-primary-700 bg-primary-700 text-white'
                  : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50') +
                (disabled ? ' cursor-not-allowed opacity-60' : '')
              }
              aria-label={`Rate ${n} of ${max}`}
              aria-pressed={active}
            >
              {n}
            </button>
          );
        })}
        {allowSkip && (
          <button
            type="button"
            disabled={disabled}
            onClick={() => onChange(null)}
            className={
              'ml-2 text-xs text-gray-500 underline-offset-2 transition-colors hover:text-gray-700 hover:underline ' +
              (disabled ? 'cursor-not-allowed opacity-60' : '')
            }
          >
            Not rated
          </button>
        )}
      </div>
      <p className="mt-1.5 text-xs text-gray-500">
        1 = Poor &middot; {max} = Excellent
      </p>
    </div>
  );
}
