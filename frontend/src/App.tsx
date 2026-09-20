import { CssBaseline, ThemeProvider } from '@mui/material'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { Provider, useSelector } from 'react-redux'
import { store, type RootState } from './store'
import { theme } from './theme'
import Layout from './components/Layout'
import Toast from './components/Toast'
import Dashboard from './pages/Dashboard'
import Breaks from './pages/Breaks'
import ClaimDetail from './pages/ClaimDetail'
import Login from './pages/Login'

/**
 * Shows the login screen when there is no token.
 *
 * This is convenience, not security. The backend rejects every unauthenticated
 * request on its own; hiding the screens simply avoids showing a dashboard
 * that would fail to load.
 */
function Gate() {
  const token = useSelector((s: RootState) => s.auth.token)
  if (!token) return <Login />

  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Dashboard />} />
          <Route path="breaks" element={<Breaks />} />
          <Route path="claims/:id" element={<ClaimDetail />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default function App() {
  return (
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <Gate />
        <Toast />
      </ThemeProvider>
    </Provider>
  )
}
