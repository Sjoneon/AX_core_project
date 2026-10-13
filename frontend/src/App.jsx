import { useCallback, useEffect, useRef, useState } from 'react'
import { apiUrl } from './api.js'
import AuthForm from './AuthForm.jsx'
import Chat from './Chat.jsx'
import MyPage from './MyPage.jsx'
import Brand from './components/Brand.jsx'
import './App.css'

const storageKey = 'homecare.accessToken'

function readToken() {
  try {
    return sessionStorage.getItem(storageKey)?.trim() || null
  } catch {
    return null
  }
}

function storeToken(token) {
  try {
    sessionStorage.removeItem(storageKey)
    if (token) sessionStorage.setItem(storageKey, token)
  } catch {
    // Login still works in memory when browser storage is unavailable.
  }
}

function App() {
  const [session, setSession] = useState(null)
  const [notice, setNotice] = useState('')
  const [page, setPage] = useState('chat')
  const [restoreToken, setRestoreToken] = useState(readToken)
  const [restoreError, setRestoreError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const sessionRef = useRef(null)
  const restoreControllerRef = useRef(null)

  const logout = useCallback((message = '') => {
    restoreControllerRef.current?.abort()
    sessionRef.current = null
    storeToken(null)
    setSession(null)
    setRestoreToken(null)
    setRestoreError('')
    setNotice(message)
    setPage('chat')
  }, [])

  const logoutSession = useCallback((message = '') => {
    if (sessionRef.current === session) logout(message)
  }, [session, logout])

  function login(data) {
    restoreControllerRef.current?.abort()
    storeToken(data.accessToken)
    sessionRef.current = data
    setSession(data)
    setRestoreToken(null)
    setRestoreError('')
    setNotice('')
    setPage('chat')
  }

  useEffect(() => {
    if (!restoreToken) return
    const controller = new AbortController()
    restoreControllerRef.current = controller
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)])

    async function restore() {
      try {
        const response = await fetch(apiUrl('/api/auth/me'), {
          headers: { Authorization: `Bearer ${restoreToken}` }, signal,
        })
        if (controller.signal.aborted) return
        signal.throwIfAborted()
        if (response.status === 401) {
          logout('로그인이 만료되었습니다. 다시 로그인해 주세요.')
          return
        }
        if (!response.ok) throw new Error('Login verification failed')
        const data = await response.json()
        if (typeof data?.email !== 'string' || !data.email.trim()) throw new Error('Invalid login response')
        if (controller.signal.aborted) return
        signal.throwIfAborted()
        const restored = { accessToken: restoreToken, email: data.email }
        sessionRef.current = restored
        setSession(restored)
        setRestoreToken(null)
      } catch {
        if (!controller.signal.aborted) setRestoreError('로그인 상태를 확인하지 못했습니다. 다시 시도해 주세요.')
      }
    }

    restore()
    return () => controller.abort()
  }, [restoreToken, attempt, logout])

  if (restoreToken) return (
    <main className="auth-page">
      <section className="auth-card" aria-label="로그인 상태 확인" aria-busy={!restoreError}>
        <Brand />
        <h1>로그인 상태 확인</h1>
        {restoreError ? <>
          <p className="error" role="alert">{restoreError}</p>
          <div className="product-actions">
            <button className="logout-button" type="button" onClick={() => logout()}>다시 로그인</button>
            <button className="send-button" type="button" onClick={() => { setRestoreError(''); setAttempt((previous) => previous + 1) }}>다시 시도</button>
          </div>
        </> : <p className="auth-description" role="status">로그인 확인 중…</p>}
      </section>
    </main>
  )

  return session
    ? <>
        <div hidden={page !== 'chat'}>
          <Chat session={session} onLogout={logoutSession} isActive={page === 'chat'} onMyPage={() => setPage('mypage')} />
        </div>
        {page === 'mypage' && <MyPage session={session} onLogout={logoutSession} onBack={() => setPage('chat')} />}
      </>
    : <AuthForm onLogin={login} notice={notice} />
}

export default App
