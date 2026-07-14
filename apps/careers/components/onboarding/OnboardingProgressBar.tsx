interface Props {
  value: number;
}

export default function OnboardingProgressBar({ value }: Props) {
  const clamped = Math.max(0, Math.min(100, Math.round(value)));
  return (
    <div>
      <div className="h-3 w-full overflow-hidden rounded-full bg-gray-200">
        <div
          className="h-full rounded-full bg-accent transition-all duration-500"
          style={{ width: `${clamped}%` }}
          aria-valuenow={clamped}
          aria-valuemin={0}
          aria-valuemax={100}
          role="progressbar"
        />
      </div>
      <div className="mt-2 flex items-center justify-between">
        <span className="text-xs text-gray-500">{clamped}% complete</span>
      </div>
    </div>
  );
}
