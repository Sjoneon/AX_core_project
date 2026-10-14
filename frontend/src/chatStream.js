// 스트리밍 미지원 서버는 기존 JSON 상담 API 사용 (404·405에 한정)
export async function requestChat(request, message, { signal, onDelta = () => {}, streamState = { supported: true } } = {}) {
  const options = { method: 'POST', body: JSON.stringify(message), signal }
  if (streamState.supported) {
    let response
    try {
      response = await request('/api/chat/stream', { ...options, rawResponse: true, headers: { Accept: 'text/event-stream' } })
    } catch (error) {
      if (signal?.aborted || ![404, 405].includes(error.status)) throw error
      streamState.supported = false
    }
    if (response) return readChatStream(response, onDelta)
  }
  signal?.throwIfAborted()
  const result = await request('/api/chat', options)
  if (typeof result?.reply !== 'string' || !result.reply.trim() || !Number.isSafeInteger(result.conversationId) || result.conversationId <= 0) {
    throw new Error('완료된 답변을 확인하지 못했습니다. 상담 내역을 확인해 주세요.')
  }
  return result
}

export async function readChatStream(response, onDelta) {
  if (!response.headers.get('Content-Type')?.includes('text/event-stream') || !response.body) {
    throw new Error('답변을 스트리밍으로 받지 못했습니다. 다시 시도해 주세요.')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let event = ''
  let data = []
  let result = null

  function dispatch() {
    if (!data.length) return
    let payload
    try {
      payload = JSON.parse(data.join('\n'))
    } catch {
      throw new Error('답변 형식이 올바르지 않습니다. 다시 시도해 주세요.')
    }
    if (event === 'delta') {
      if (typeof payload.content !== 'string') throw new Error('답변 형식이 올바르지 않습니다.')
      onDelta(payload.content)
    } else if (event === 'done') {
      if (typeof payload.reply !== 'string' || !payload.reply.trim() || !Number.isSafeInteger(payload.conversationId) || payload.conversationId <= 0) {
        throw new Error('완료된 답변을 확인하지 못했습니다. 상담 내역을 확인해 주세요.')
      }
      result = payload
    } else if (event === 'error') {
      throw new Error(payload.message || 'AI 응답을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.')
    }
  }

  try {
    while (!result) {
      const { value, done } = await reader.read()
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true })
      let newline
      while (!result && (newline = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, newline).replace(/\r$/, '')
        buffer = buffer.slice(newline + 1)
        if (!line) {
          dispatch()
          event = ''
          data = []
        } else if (line.startsWith('event:')) {
          event = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          data.push(line.slice(5).replace(/^ /, ''))
        }
      }
      if (done) break
    }
    if (!result) throw new Error('답변 수신이 중단되었습니다. 상담 내역을 확인한 후 다시 시도해 주세요.')
    return result
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
