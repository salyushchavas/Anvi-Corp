// I-9 acceptable document lists (USCIS reference).
//
// List A: documents establishing both identity AND employment authorization.
// List B: identity only.
// List C: employment authorization only.
//
// "Other" is included as the last item in each list so HR can record
// edge-case documents in the form's "Additional information" textarea.

export const LIST_A_DOCUMENTS = [
  'U.S. Passport',
  'U.S. Passport Card',
  'Permanent Resident Card (Form I-551)',
  'Employment Authorization Document (Form I-766)',
  'Foreign Passport with Form I-94 and endorsement',
  'Foreign Passport with Form I-94 (FSM/RMI citizens)',
  'Other (specify in Additional Information)',
];

export const LIST_B_DOCUMENTS = [
  "Driver's License (issued by a U.S. state or territory)",
  'ID Card issued by a state or territory',
  'School ID with photograph',
  "Voter's Registration Card",
  'U.S. Military Card or Draft Record',
  "Military Dependent's ID Card",
  'U.S. Coast Guard Merchant Mariner Card',
  'Native American Tribal Document',
  "Driver's License issued by a Canadian government authority",
  'Other (specify in Additional Information)',
];

export const LIST_C_DOCUMENTS = [
  'Social Security Account Number card (unrestricted)',
  'Certification of Birth Abroad (Form DS-1350)',
  'Original or certified copy of U.S. birth certificate',
  'Native American Tribal Document',
  'U.S. Citizen ID Card (Form I-197)',
  'Resident Citizen ID Card (Form I-179)',
  'Employment Authorization Document issued by DHS',
  'Other (specify in Additional Information)',
];
