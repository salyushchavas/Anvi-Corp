'use client';

import { useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import ProvideInfoModal from './ProvideInfoModal';

interface Props {
  applicationId: string;
  infoRequestedFieldsCsv: string | null;
  onProvided: () => void;
}

const LABEL: Record<string, string> = {
  resume: 'Updated resume',
  workAuth: 'Work authorization details',
  education: 'Education verification',
  other: 'Additional details',
};

export default function InfoRequestedBanner({
  applicationId,
  infoRequestedFieldsCsv,
  onProvided,
}: Props) {
  const [open, setOpen] = useState(false);
  const fields = (infoRequestedFieldsCsv ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);

  return (
    <>
      <div className="mb-4 flex items-start gap-3 rounded-md border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
        <AlertTriangle
          className="mt-0.5 h-5 w-5 flex-shrink-0 text-amber-600"
          strokeWidth={2}
        />
        <div className="flex-1">
          <p className="font-semibold">Information requested by ERM</p>
          <p className="mt-1 text-[13px]">
            Please provide the following before we can continue your review:
          </p>
          <ul className="mt-2 list-inside list-disc text-[13px]">
            {fields.length === 0 ? (
              <li>Additional information</li>
            ) : (
              fields.map((f) => <li key={f}>{LABEL[f] ?? f}</li>)
            )}
          </ul>
        </div>
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="rounded-md bg-amber-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-amber-700"
        >
          Provide information
        </button>
      </div>
      {open && (
        <ProvideInfoModal
          applicationId={applicationId}
          fields={fields}
          onClose={() => setOpen(false)}
          onProvided={() => {
            setOpen(false);
            onProvided();
          }}
        />
      )}
    </>
  );
}
