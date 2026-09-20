import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

/**
 * Local UI state -- the things RTK Query does not own.
 *
 * Worth keeping the distinction clear: RTK Query holds *server* state (what the
 * backend says), this holds *client* state (what this browser is currently
 * doing). Mixing the two is how caches go stale and filters get lost.
 */
interface UiState {
  breakTypeFilter: string | null
  toast: { message: string; severity: 'success' | 'error' } | null
}

const initialState: UiState = { breakTypeFilter: null, toast: null }

const uiSlice = createSlice({
  name: 'ui',
  initialState,
  reducers: {
    setBreakTypeFilter(state, action: PayloadAction<string | null>) {
      state.breakTypeFilter = action.payload
    },
    showToast(state, action: PayloadAction<UiState['toast']>) {
      state.toast = action.payload
    },
    clearToast(state) {
      state.toast = null
    },
  },
})

export const { setBreakTypeFilter, showToast, clearToast } = uiSlice.actions
export default uiSlice.reducer
