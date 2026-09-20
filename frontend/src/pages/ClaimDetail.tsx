import { useState } from 'react'
import {
  Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Divider,
  LinearProgress, Paper, Stack, Step, StepLabel, Stepper, TextField, Typography,
} from '@mui/material'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import { motion } from 'framer-motion'
import { useNavigate, useParams } from 'react-router-dom'
import { useDispatch } from 'react-redux'
import {
  useGetDisputeHistoryQuery, useGetDisputeQuery, useTransitionDisputeMutation,
} from '../store/api'
import { ALLOWED_NEXT, rupees, type DisputeStatus } from '../types'
import { STATUS_COLOR } from '../theme'
import { showToast } from '../store/uiSlice'

/** The happy path, shown as a stepper. Terminal failures fall outside it. */
const FLOW: DisputeStatus[] = ['DRAFT', 'FILED', 'ACKNOWLEDGED', 'ACCEPTED', 'RECOVERED']

export default function ClaimDetail() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const dispatch = useDispatch()

  const { data: claim, isLoading } = useGetDisputeQuery(id)
  const { data: history = [] } = useGetDisputeHistoryQuery(id)
  const [transition, { isLoading: moving }] = useTransitionDisputeMutation()

  const [settleOpen, setSettleOpen] = useState(false)
  const [amount, setAmount] = useState('')

  if (isLoading || !claim) return <LinearProgress />

  const step = FLOW.indexOf(claim.status)
  const failed = ['REJECTED', 'WITHDRAWN'].includes(claim.status)

  const move = async (target: DisputeStatus, recoveredPaise?: number) => {
    try {
      await transition({ id, target, recoveredPaise, note: `Moved to ${target} from the console` }).unwrap()
      dispatch(showToast({ message: `Claim moved to ${target.toLowerCase()}`, severity: 'success' }))
    } catch (e: unknown) {
      // 409 for an illegal transition, 422 for a refused claim. Both carry a
      // readable reason, so we show the server's message rather than inventing one.
      const err = e as { data?: { message?: string } }
      dispatch(showToast({ message: err.data?.message ?? 'Transition refused', severity: 'error' }))
    }
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/breaks')} sx={{ mb: 1 }}>
          Back to breaks
        </Button>
        <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
          <Typography variant="h5">Claim</Typography>
          <Chip color={STATUS_COLOR[claim.status]} label={claim.status.toLowerCase()} />
        </Stack>
        <Typography variant="caption" color="text.secondary"
                    sx={{ fontFamily: 'ui-monospace, monospace' }}>
          {claim.id}
        </Typography>
      </Box>

      <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap' }}>
        <Paper sx={{ p: 2.5, flex: '1 1 200px' }}>
          <Typography variant="caption" color="text.secondary">CLAIMED</Typography>
          <Typography variant="h5">{rupees(claim.claimedPaise)}</Typography>
        </Paper>
        <Paper sx={{ p: 2.5, flex: '1 1 200px' }}>
          <Typography variant="caption" color="text.secondary">RECOVERED</Typography>
          <Typography variant="h5"
                      sx={{ color: claim.recoveredPaise ? 'success.main' : 'text.secondary' }}>
            {rupees(claim.recoveredPaise)}
          </Typography>
        </Paper>
      </Stack>

      <Paper sx={{ p: 3 }}>
        {failed ? (
          <Stack spacing={1}>
            <Typography variant="subtitle2">Closed without recovery</Typography>
            <Typography variant="body2" color="text.secondary">
              This claim ended as {claim.status.toLowerCase()} and cannot be reopened.
            </Typography>
          </Stack>
        ) : (
          <Stepper activeStep={step} alternativeLabel>
            {FLOW.map((s) => (
              <Step key={s}><StepLabel>{s.toLowerCase()}</StepLabel></Step>
            ))}
          </Stepper>
        )}

        <Divider sx={{ my: 3 }} />

        <Typography variant="subtitle2" sx={{ mb: 1.5 }}>Available actions</Typography>
        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
          {ALLOWED_NEXT[claim.status].map((target) => (
            <Button
              key={target} disabled={moving}
              variant={target === 'RECOVERED' ? 'contained' : 'outlined'}
              color={target === 'REJECTED' || target === 'WITHDRAWN' ? 'inherit' : 'primary'}
              onClick={() => target === 'RECOVERED' ? setSettleOpen(true) : move(target)}
            >
              {target.toLowerCase()}
            </Button>
          ))}
          {ALLOWED_NEXT[claim.status].length === 0 && (
            <Typography variant="body2" color="text.secondary">
              None — this is a terminal state.
            </Typography>
          )}
        </Stack>
      </Paper>

      <Paper sx={{ p: 3 }}>
        <Typography variant="subtitle2" sx={{ mb: 2 }}>History</Typography>
        <Stack spacing={0}>
          {history.map((h, i) => (
            <motion.div key={i}
                        initial={{ opacity: 0, x: -8 }}
                        animate={{ opacity: 1, x: 0 }}
                        transition={{ delay: i * 0.05, duration: 0.25 }}>
              <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-start', py: 1.25 }}>
                <Box sx={{ width: 10, height: 10, mt: 0.75, borderRadius: '50%',
                           bgcolor: h.toStatus === 'RECOVERED' ? 'success.main' : 'primary.main',
                           flexShrink: 0 }} />
                <Box sx={{ flex: 1 }}>
                  <Typography variant="body2">
                    <strong>{h.fromStatus ?? 'created'}</strong> → <strong>{h.toStatus}</strong>
                    {h.recoveredPaise != null && ` · ${rupees(h.recoveredPaise)}`}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {h.actor} · {new Date(h.occurredAt).toLocaleString('en-IN')}
                    {h.note && ` · ${h.note}`}
                  </Typography>
                </Box>
              </Stack>
            </motion.div>
          ))}
        </Stack>
      </Paper>

      <Dialog open={settleOpen} onClose={() => setSettleOpen(false)} fullWidth maxWidth="xs">
        <DialogTitle>Settle claim</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Claimed {rupees(claim.claimedPaise)}. Partial settlement is normal;
            recovering more than was claimed is refused.
          </Typography>
          <TextField
            autoFocus fullWidth label="Amount recovered (₹)" type="number"
            value={amount} onChange={(e) => setAmount(e.target.value)}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSettleOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            onClick={() => {
              setSettleOpen(false)
              move('RECOVERED', Math.round(parseFloat(amount || '0') * 100))
            }}
          >
            Settle
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  )
}
