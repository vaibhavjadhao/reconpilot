import { CssBaseline, ThemeProvider } from '@mui/material'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { Provider } from 'react-redux'
import { store } from './store'
import { theme } from './theme'
import Layout from './components/Layout'
import Toast from './components/Toast'
import Dashboard from './pages/Dashboard'
import Breaks from './pages/Breaks'
import ClaimDetail from './pages/ClaimDetail'

export default function App() {
  return (
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        {/* Normalises browser defaults and applies the theme background. */}
        <CssBaseline />
        <BrowserRouter>
          <Routes>
            <Route element={<Layout />}>
              <Route index element={<Dashboard />} />
              <Route path="breaks" element={<Breaks />} />
              <Route path="claims/:id" element={<ClaimDetail />} />
            </Route>
          </Routes>
        </BrowserRouter>
        <Toast />
      </ThemeProvider>
    </Provider>
  )
}
