// Single source of truth on the frontend for which date inputs each
// visa track needs on the registration / profile form. Mirrors the
// Java map in
// `backend/src/main/java/com/skyzen/careers/enums/VisaDateRequirement.java`
// — keep both in sync.

import type { WorkAuthTrack } from '@/types';

export type VisaDateRequirement = 'NONE' | 'END_ONLY' | 'BOTH';

export const VISA_DATE_REQUIREMENT_BY_TRACK: Record<WorkAuthTrack, VisaDateRequirement> = {
  CITIZEN: 'NONE',
  CPT: 'END_ONLY',
  OPT: 'END_ONLY',
  STEM_OPT: 'END_ONLY',
  OTHER: 'BOTH',
};

export function visaDateRequirementFor(
  track: WorkAuthTrack | '' | null | undefined,
): VisaDateRequirement {
  if (!track) return 'NONE';
  return VISA_DATE_REQUIREMENT_BY_TRACK[track] ?? 'BOTH';
}

/** Friendly label shown next to the work-auth date inputs. */
export const VISA_TRACK_LABEL: Record<WorkAuthTrack, string> = {
  CITIZEN: 'US Citizen',
  CPT: 'CPT',
  OPT: 'OPT',
  STEM_OPT: 'STEM OPT',
  OTHER: 'Other (H-1B, GC, EAD, etc.)',
};
