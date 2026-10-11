import { useEffect, useRef, useState } from 'react'
import AuthForm from './AuthForm.jsx'
import './App.css'

function App() {
  const [session, setSession] = useState(null)
  const [notice, setNotice] = useState('')

  function logout(message = '') {
    setSession(null)
    setNotice(message)
  }

  return session
    ? <Chat session={session} onLogout={logout} />
    : <AuthForm onLogin={setSession} notice={notice} />
}

function Chat({ session, onLogout }) {
  const [messages, setMessages] = useState([])
  const [draft, setDraft] = useState('')
  const [isSending, setIsSending] = useState(false)
  const [error, setError] = useState('')
  const conversationRef = useRef(null)
  const inputRef = useRef(null)

  useEffect(() => {
    const conversation = conversationRef.current
    conversation.scrollTop = conversation.scrollHeight
    if (!isSending) inputRef.current.focus()
  }, [messages, isSending])

  async function sendMessage(event) {
    event.preventDefault()
    const message = draft.trim()
    if (!message || isSending) return

    setError('')
    setIsSending(true)
    setDraft('')
    setMessages((previous) => [
      ...previous,
      { id: crypto.randomUUID(), role: 'user', content: message },
    ])

    try {
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${session.accessToken}`,
        },
        body: JSON.stringify({ message }),
        signal: AbortSignal.timeout(35_000),
      })
      const data = await response.json().catch(() => null)

      if (response.status === 401) {
        onLogout('로그인이 만료되었습니다. 다시 로그인해 주세요.')
        return
      }

      if (!response.ok) {
        throw new Error(data?.message || '답변을 가져오지 못했습니다. 서버 실행 상태를 확인해 주세요.')
      }
      if (typeof data?.reply !== 'string' || !data.reply.trim()) {
        throw new Error('답변이 비어 있습니다. 다시 시도해 주세요.')
      }

      setMessages((previous) => [
        ...previous,
        { id: crypto.randomUUID(), role: 'assistant', content: data.reply },
      ])
    } catch (exception) {
      setMessages((previous) => previous.slice(0, -1))
      setDraft(message)
      setError(
        exception.name === 'TimeoutError'
          ? '응답 시간이 초과되었습니다. 다시 시도해 주세요.'
          : exception instanceof TypeError
            ? '서버에 연결하지 못했습니다. 서버 실행 상태를 확인해 주세요.'
            : exception.message,
      )
    } finally {
      setIsSending(false)
    }
  }

  function handleKeyDown(event) {
    if (
      event.key === 'Enter' &&
      !event.shiftKey &&
      !event.nativeEvent.isComposing &&
      event.nativeEvent.keyCode !== 229
    ) {
      sendMessage(event)
    }
  }

  function clearMessages() {
    setMessages([])
    setError('')
  }

  return (
    <main className="app">
      <header className="page-header">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">AI</span>
          <div>
            <h1>AI 챗봇</h1>
            <p className="signed-in-email">{session.email}</p>
          </div>
        </div>
        <div className="header-actions">
          <button
            className="clear-button"
            type="button"
            onClick={clearMessages}
            disabled={isSending || messages.length === 0}
          >
            화면 비우기
          </button>
          <button className="clear-button" type="button" onClick={() => onLogout()} disabled={isSending}>
            로그아웃
          </button>
        </div>
      </header>

      <section className="chat-panel" aria-label="AI 채팅">
        <div className="conversation" ref={conversationRef} role="log" aria-label="질문과 답변" aria-busy={isSending}>
          {messages.length === 0 && (
            <div className="empty-state">
              <span className="empty-mark" aria-hidden="true">✦</span>
              <h2>어떤 이야기를 나눠볼까요?</h2>
              <p>아래에 질문을 입력하면 AI가 답변해 드립니다.</p>
            </div>
          )}
          {messages.map((message) => (
            <article className={`message ${message.role}`} key={message.id}>
              <span className="message-label">{message.role === 'user' ? '나' : 'AI'}</span>
              <p className="message-content">{message.content}</p>
            </article>
          ))}
          {isSending && <p className="loading" role="status">AI가 답변을 작성하고 있습니다…</p>}
        </div>

        <form className="composer" onSubmit={sendMessage}>
          {error && <p className="error" role="alert">{error}</p>}
          <label htmlFor="message">메시지</label>
          <div className="input-row">
            <textarea
              id="message"
              ref={inputRef}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="질문을 입력해 주세요."
              maxLength={4000}
              readOnly={isSending}
              required
              rows={3}
              aria-describedby="input-hint"
            />
            <button className="send-button" type="submit" disabled={isSending || !draft.trim()}>
              {isSending ? '응답 대기' : '보내기'}
            </button>
          </div>
          <div className="input-meta" id="input-hint">
            <span>Enter 전송 · Shift + Enter 줄바꿈</span>
            <span>{draft.length.toLocaleString()} / 4,000</span>
          </div>
        </form>
      </section>
      <p className="footnote">이전 대화는 화면에만 표시되며, 각 질문은 독립적으로 처리됩니다.</p>
    </main>
  )
}

export default App
