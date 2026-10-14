import { useEffect, useState } from 'react'

const cellStyle = { padding: '8px', whiteSpace: 'nowrap', verticalAlign: 'top' }

export default function ToolCallTrace({ request, conversationId, message }) {
  const [result, setResult] = useState({ conversationId: null, messageId: null, calls: [], error: '' })
  const messageId = message?.id

  useEffect(() => {
    if (!conversationId || !messageId) return
    const controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)])

    // 새 답변과 이전 상담의 마지막 답변을 기준으로 전체 호출 이력 조회
    request(`/api/conversations/${conversationId}/tool-calls`, { signal })
      .then((calls) => {
        if (!signal.aborted) setResult({ conversationId, messageId, calls, error: '' })
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setResult({ conversationId, messageId, calls: [], error: '개발용 호출 이력을 불러오지 못했습니다.' })
        }
      })
    return () => controller.abort()
  }, [request, conversationId, messageId])

  if (!conversationId || !message) return null
  const current = result.conversationId === conversationId && result.messageId === messageId
  const calls = current ? result.calls : []

  return (
    <section className="tool-result" aria-label="개발용 MCP 도구 호출 확인">
      <h3>개발용 MCP 도구 호출 확인 <small>DEV 전용</small></h3>
      <p>상담 전체 호출 이력입니다. 메시지 ID로 답변을 구분하며, 마지막 답변의 원본을 아래에서 확인할 수 있습니다.</p>
      {!current && <p className="loading" role="status">도구 호출 이력 확인 중…</p>}
      {current && result.error && <p className="error" role="alert">{result.error}</p>}
      {current && !result.error && calls.length === 0 && <p>저장된 도구 호출 이력이 없습니다.</p>}
      {calls.length > 0 && (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', textAlign: 'left', borderCollapse: 'collapse' }}>
            <thead>
              <tr>{['메시지 ID', '순서', '도구', '선택 주체', 'status', '오류 코드', '소요 시간'].map((label) => <th key={label} scope="col" style={cellStyle}>{label}</th>)}</tr>
            </thead>
            <tbody>
              {calls.map((call, index) => (
                <tr key={`${call.messageId}-${call.callOrder}-${index}`}>
                  <td style={cellStyle}>{call.messageId ?? '—'}</td>
                  <td style={cellStyle}>{call.callOrder}</td>
                  <td style={cellStyle}>{call.toolName}</td>
                  <td style={cellStyle}>{call.selectionSource}</td>
                  <td style={cellStyle}>{call.status}</td>
                  <td style={cellStyle}>{call.errorCode || '—'}</td>
                  <td style={cellStyle}>{call.elapsedMs} ms</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <details className="message-sources">
        <summary>원본 tools JSON</summary>
        <pre className="source-content">{JSON.stringify(message.tools || {}, null, 2)}</pre>
      </details>
      <details className="message-sources">
        <summary>원본 답변과 검색 근거</summary>
        <pre className="source-content">{JSON.stringify({ content: message.content, sources: message.sources || [] }, null, 2)}</pre>
      </details>
    </section>
  )
}
