// Unsaved work stays only in this page's memory and is restored to the same account/server.
export function createSessionDraftRecovery() {
  let saved = null
  return {
    remember(current, pending) {
      if (current.userId == null) return
      const previous = saved?.userId === current.userId && saved?.base === current.base ? saved : null
      const unsent = pending && !pending.accepted ? pending : null
      saved = {
        ...current,
        ...((current.continuationHistoryId || unsent?.continuationHistoryId || previous?.continuationHistoryId)?{continuationHistoryId:current.continuationHistoryId || unsent?.continuationHistoryId || previous?.continuationHistoryId}:{}),
        ...((current.dailyIssueDate || unsent?.dailyIssueDate || previous?.dailyIssueDate)?{dailyIssueDate:current.dailyIssueDate || unsent?.dailyIssueDate || previous?.dailyIssueDate}:{}),
        conversationId: current.conversationId || previous?.conversationId || '',
        roleId: current.roleId ?? previous?.roleId,
        text: [...new Set([previous?.text, unsent?.message, current.text].filter(Boolean))].join('\n\n'),
        images: [...new Set([...(previous?.images || []), ...(unsent?.images || []), ...(current.images || [])])],
      }
    },
    take(userId, base) {
      const result = saved?.userId === userId && saved?.base === base ? saved : null
      saved = null
      return result
    },
    clear() { saved = null },
  }
}
