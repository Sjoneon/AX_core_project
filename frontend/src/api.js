import { useCallback } from 'react'

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')

export function apiUrl(path) {
  return `${apiBaseUrl}${path}`
}

export function useAuthenticatedRequest(session, onLogout) {
  return useCallback(async (path, options = {}) => {
    const { rawResponse = false, ...fetchOptions } = options
    const response = await fetch(apiUrl(path), {
      ...fetchOptions,
      headers: {
        'Content-Type': 'application/json',
        ...fetchOptions.headers,
        Authorization: `Bearer ${session.accessToken}`,
      },
      signal: options.signal || AbortSignal.timeout(35_000),
    })
    if (response.status === 401) {
      onLogout('로그인이 만료되었습니다. 다시 로그인해 주세요.')
      throw new Error('다시 로그인해 주세요.')
    }
    if (!response.ok) {
      const data = await response.json().catch(() => null)
      const error = new Error(data?.message || '요청을 처리하지 못했습니다. 서버 실행 상태를 확인해 주세요.')
      error.status = response.status
      throw error
    }
    return rawResponse ? response : response.json().catch(() => null)
  }, [session.accessToken, onLogout])
}
