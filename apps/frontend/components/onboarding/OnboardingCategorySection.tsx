import OnboardingTaskCard from './OnboardingTaskCard';
import type {
  OnboardingCategory,
  OnboardingTaskResponse,
} from '@/types';

const CATEGORY_LABEL: Record<OnboardingCategory, string> = {
  PAPERWORK: 'Paperwork',
  COMPLIANCE: 'Compliance & Verification',
  SETUP: 'Account & Tool Setup',
  INTRODUCTION: 'Team & Orientation',
};

interface Props {
  category: OnboardingCategory;
  tasks: OnboardingTaskResponse[];
  onTaskUpdated: (updated: OnboardingTaskResponse) => void;
}

export default function OnboardingCategorySection({
  category,
  tasks,
  onTaskUpdated,
}: Props) {
  if (tasks.length === 0) return null;
  const completed = tasks.filter((t) => t.status === 'COMPLETED').length;
  return (
    <section className="mt-8 first:mt-0">
      <h2 className="mb-3 flex items-baseline gap-2">
        <span className="text-base font-semibold text-gray-900">
          {CATEGORY_LABEL[category]}
        </span>
        <span className="text-xs text-gray-500">
          ({completed} of {tasks.length})
        </span>
      </h2>
      <div className="flex flex-col gap-3">
        {tasks.map((t) => (
          <OnboardingTaskCard key={t.id} task={t} onUpdated={onTaskUpdated} />
        ))}
      </div>
    </section>
  );
}
