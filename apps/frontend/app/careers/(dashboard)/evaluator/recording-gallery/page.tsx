'use client';

import RecordingGallery from '@/components/recordings/RecordingGallery';

export default function EvaluatorRecordingGalleryPage() {
  return (
    <RecordingGallery
      hideInterviews
      title="Recording Gallery"
      subtitle="Your approved evaluation recordings — pending / revision-requested items stay hidden until the manager clears them."
    />
  );
}
