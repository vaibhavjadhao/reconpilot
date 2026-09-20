import { useState } from 'react'
import {
  Alert, Box, Button, Divider, Paper, Stack, TextField, Typography,
} from '@mui/material'
import InsightsIcon from '@mui/icons-material/Insights'
import { motion } from 'framer-motion'
import { useDispatch } from 'react-redux'
import { useLoginMutation, useRegisterMutation } from '../store/api'
import { signedIn } from '../store/authSlice'

export default function Login() {
  const dispatch = useDispatch()
  const [login, { isLoading: loggingIn }] = useLoginMutation()
  const [register, { isLoading: registering }] = useRegisterMutation()

  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [tenantName, setTenantName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      if (mode === 'register') {
        await register({ tenantName, email, password }).unwrap()
      }
      const r = await login({ email, password }).unwrap()
      dispatch(signedIn({ token: r.token, email: r.email, role: r.role }))
    } catch {
      // The server answers the same way for an unknown email and a wrong
      // password, so the client must not guess at a more specific message.
      setError(mode === 'register'
        ? 'Could not create that workspace. The email may already be registered.'
        : 'Those credentials were not accepted.')
    }
  }

  return (
    <Box sx={{ minHeight: '100dvh', display: 'grid', placeItems: 'center',
               bgcolor: 'background.default', p: 2 }}>
      <motion.div
        initial={{ opacity: 0, y: 18 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4, ease: [0.22, 1, 0.36, 1] }}
        style={{ width: '100%', maxWidth: 420 }}
      >
        <Paper sx={{ p: 4 }} component="form" onSubmit={submit}>
          <Stack spacing={1} sx={{ alignItems: 'center', mb: 3 }}>
            <InsightsIcon sx={{ fontSize: 38, color: 'primary.main' }} />
            <Typography variant="h5">ReconPilot</Typography>
            <Typography variant="body2" color="text.secondary" align="center">
              {mode === 'login'
                ? 'Sign in to your workspace'
                : 'Create a workspace and its first user'}
            </Typography>
          </Stack>

          <Stack spacing={2}>
            {mode === 'register' && (
              <TextField label="Workspace name" value={tenantName} required
                         onChange={(e) => setTenantName(e.target.value)} fullWidth />
            )}
            <TextField label="Email" type="email" value={email} required autoComplete="username"
                       onChange={(e) => setEmail(e.target.value)} fullWidth />
            <TextField label="Password" type="password" value={password} required
                       autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                       onChange={(e) => setPassword(e.target.value)} fullWidth />

            {error && <Alert severity="error">{error}</Alert>}

            <Button type="submit" variant="contained" size="large"
                    disabled={loggingIn || registering}>
              {mode === 'login' ? 'Sign in' : 'Create workspace'}
            </Button>
          </Stack>

          <Divider sx={{ my: 3 }} />

          <Typography variant="body2" align="center" color="text.secondary">
            {mode === 'login' ? 'No workspace yet?' : 'Already have one?'}{' '}
            <Button size="small" onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(null) }}>
              {mode === 'login' ? 'Create one' : 'Sign in'}
            </Button>
          </Typography>

          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
            Each workspace is a separate tenant. Row-level security in PostgreSQL
            means one workspace cannot read another's data even if it knows a row id.
          </Typography>
        </Paper>
      </motion.div>
    </Box>
  )
}
