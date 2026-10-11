import { useCallback, useState } from 'react'
import AuthForm from './AuthForm.jsx'
import Chat from './Chat.jsx'
import './App.css'

function App() {
  const [session, setSession] = useState(null)
  const [notice, setNotice] = useState('')

  const logout = useCallback((message = '') => {
    setSession(null)
    setNotice(message)
  }, [])

  return session
    ? <Chat session={session} onLogout={logout} />
    : <AuthForm onLogin={setSession} notice={notice} />
}

export default App
