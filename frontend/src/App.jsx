import { useCallback, useState } from 'react'
import AuthForm from './AuthForm.jsx'
import Chat from './Chat.jsx'
import MyPage from './MyPage.jsx'
import './App.css'

function App() {
  const [session, setSession] = useState(null)
  const [notice, setNotice] = useState('')
  const [page, setPage] = useState('chat')

  const logout = useCallback((message = '') => {
    setSession(null)
    setNotice(message)
    setPage('chat')
  }, [])

  return session
    ? <>
        <div hidden={page !== 'chat'}>
          <Chat session={session} onLogout={logout} isActive={page === 'chat'} onMyPage={() => setPage('mypage')} />
        </div>
        {page === 'mypage' && <MyPage session={session} onLogout={logout} onBack={() => setPage('chat')} />}
      </>
    : <AuthForm onLogin={setSession} notice={notice} />
}

export default App
