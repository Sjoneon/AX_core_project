import { useState } from 'react'
import { apiUrl } from './api.js'
import Brand from './components/Brand.jsx'

function AuthForm({ onLogin, notice }) {
  const [isSignup, setIsSignup] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')

  async function submit(event) {
    event.preventDefault()
    if (isSubmitting) return
    setError('')
    setSuccess('')
    setIsSubmitting(true)

    try {
      const response = await fetch(apiUrl(`/api/auth/${isSignup ? 'signup' : 'login'}`), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: email.trim(), password }),
        signal: AbortSignal.timeout(10_000),
      })
      const data = await response.json().catch(() => null)
      if (!response.ok) {
        throw new Error(data?.message || '요청을 처리하지 못했습니다. 서버 실행 상태를 확인해 주세요.')
      }

      if (isSignup) {
        setIsSignup(false)
        setPassword('')
        setSuccess('회원가입이 완료되었습니다. 로그인해 주세요.')
      } else {
        if (typeof data?.accessToken !== 'string' || typeof data?.email !== 'string') {
          throw new Error('로그인 응답을 확인하지 못했습니다. 다시 시도해 주세요.')
        }
        onLogin(data)
      }
    } catch (exception) {
      setError(
        exception.name === 'TimeoutError'
          ? '응답 시간이 초과되었습니다. 다시 시도해 주세요.'
          : exception instanceof TypeError
            ? '서버에 연결하지 못했습니다. 서버 실행 상태를 확인해 주세요.'
            : exception.message,
      )
    } finally {
      setIsSubmitting(false)
    }
  }

  function switchMode() {
    setIsSignup(!isSignup)
    setPassword('')
    setError('')
    setSuccess('')
  }

  return (
    <main className="auth-page">
      <section className="auth-card" aria-label={isSignup ? '회원가입' : '로그인'}>
        <Brand />
        <h1>{isSignup ? '회원가입' : 'HomeCare AI 로그인'}</h1>
        <p className="auth-description">
          {isSignup ? '계정을 만들고 우리 집 가전 상담을 시작해 보세요.' : '로그인하고 가전에 대한 궁금한 점을 물어보세요.'}
        </p>
        {error && <p className="error" role="alert">{error}</p>}
        {notice && !error && !success && <p className="error" role="status">{notice}</p>}
        {success && <p className="success" role="status">{success}</p>}

        <form className="auth-form" onSubmit={submit}>
          <label htmlFor="email">이메일</label>
          <input
            id="email"
            type="email"
            autoComplete="username"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder="name@example.com"
            maxLength={254}
            disabled={isSubmitting}
            required
          />
          <label htmlFor="password">비밀번호</label>
          <input
            id="password"
            type="password"
            autoComplete={isSignup ? 'new-password' : 'current-password'}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            minLength={isSignup ? 8 : undefined}
            maxLength={72}
            disabled={isSubmitting}
            aria-describedby={isSignup ? 'password-hint' : undefined}
            required
          />
          {isSignup && <p className="password-hint" id="password-hint">8자 이상, UTF-8 기준 72바이트 이하로 입력해 주세요.</p>}
          <button className="send-button auth-submit" type="submit" disabled={isSubmitting}>
            {isSubmitting ? '처리 중…' : isSignup ? '회원가입' : '로그인'}
          </button>
        </form>

        <button className="auth-switch" type="button" onClick={switchMode} disabled={isSubmitting}>
          {isSignup ? '이미 계정이 있나요? 로그인' : '계정이 없나요? 회원가입'}
        </button>
      </section>
      <p className="footnote">새로고침하거나 창을 닫으면 다시 로그인해야 합니다.</p>
    </main>
  )
}

export default AuthForm
