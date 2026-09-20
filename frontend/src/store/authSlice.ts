import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

export interface AuthState {
  token: string | null
  email: string | null
  role: string | null
}

const STORAGE_KEY = 'reconpilot.auth'

/**
 * Restores a session across page reloads.
 *
 * localStorage is readable by any script on this origin, so a token kept there
 * is exposed to any XSS. The alternative -- an httpOnly cookie the browser
 * attaches automatically -- is not reachable from JavaScript at all, and is
 * what a production deployment should use. It is kept simple here, and the
 * tradeoff is recorded rather than hidden.
 */
function load(): AuthState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : { token: null, email: null, role: null }
  } catch {
    return { token: null, email: null, role: null }
  }
}

const authSlice = createSlice({
  name: 'auth',
  initialState: load(),
  reducers: {
    signedIn(state, action: PayloadAction<{ token: string; email: string; role: string }>) {
      state.token = action.payload.token
      state.email = action.payload.email
      state.role = action.payload.role
      try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)) } catch { /* private mode */ }
    },
    signedOut(state) {
      state.token = null
      state.email = null
      state.role = null
      try { localStorage.removeItem(STORAGE_KEY) } catch { /* private mode */ }
    },
  },
})

export const { signedIn, signedOut } = authSlice.actions
export default authSlice.reducer
