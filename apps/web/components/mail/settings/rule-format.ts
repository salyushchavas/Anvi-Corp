import type { MailCustomFolder, MailFolder, MailRuleAction, MailRuleCondition } from "@/lib/mail-types";

const FIELD_LABEL: Record<string, string> = {
  FROM: "From",
  TO: "To",
  CC: "Cc",
  SUBJECT: "Subject",
  HAS_ATTACHMENT: "Has attachment",
};

const OP_LABEL: Record<string, string> = {
  CONTAINS: "contains",
  EQUALS: "equals",
  IS_TRUE: "is true",
};

export const SYS_FOLDER_LABEL: Record<MailFolder, string> = {
  INBOX: "Inbox",
  SENT: "Sent",
  DRAFTS: "Drafts",
  ARCHIVE: "Archive",
  TRASH: "Trash",
};

export function describeCondition(c: MailRuleCondition): string {
  if (c.field === "HAS_ATTACHMENT") return "has an attachment";
  return `${FIELD_LABEL[c.field]} ${OP_LABEL[c.operator]} "${c.value ?? ""}"`;
}

export function describeConditions(conditions: MailRuleCondition[]): string {
  if (!conditions.length) return "—";
  return conditions.map(describeCondition).join(", ");
}

export function describeAction(a: MailRuleAction, folders: MailCustomFolder[]): string {
  switch (a.type) {
    case "MOVE_TO_SYSTEM_FOLDER":
      return `Move to ${a.targetSystemFolder ? SYS_FOLDER_LABEL[a.targetSystemFolder] : "folder"}`;
    case "MOVE_TO_CUSTOM_FOLDER": {
      const f = folders.find((x) => x.id === a.targetCustomFolderId);
      return `Move to ${f ? f.name : "(deleted folder)"}`;
    }
    case "MARK_READ":
      return "Mark read";
    case "STAR":
      return "Star";
    case "MARK_IMPORTANT":
      return "Mark important";
    case "DELETE":
      return "Move to Trash";
    default:
      return "";
  }
}
