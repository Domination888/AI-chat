// One pending user decision at a time. Cancellation never continues a mutation.
export function createActionRequest(present) {
  let resolvePending = null
  let requestId = 0
  function settle(value = null) {
    const resolve = resolvePending
    resolvePending = null
    present(null)
    resolve?.(value)
  }
  function ask(options) {
    settle()
    return new Promise(resolve => {
      resolvePending = resolve
      present({ ...options, requestId: ++requestId })
    })
  }
  return { ask, settle }
}
