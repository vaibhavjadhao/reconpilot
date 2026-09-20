import {
  Box, Button, Chip, CircularProgress, Paper, Stack, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow, Tooltip, Typography,
} from '@mui/material'
import AddCircleIcon from '@mui/icons-material/AddCircleOutlineOutlined'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { useDispatch, useSelector } from 'react-redux'
import { useCreateDisputeMutation, useGetBreaksQuery } from '../store/api'
import { rupees, type BreakView } from '../types'
import { BREAK_COLOR, STATUS_COLOR } from '../theme'
import { setBreakTypeFilter, showToast } from '../store/uiSlice'
import type { RootState } from '../store'

const TYPES = ['CHARGED_WHEN_EXEMPT', 'CAP_BREACHED', 'OVERCHARGED', 'UNDERCHARGED'] as const
const MotionRow = motion(TableRow)

export default function Breaks() {
  const dispatch = useDispatch()
  const navigate = useNavigate()
  const filter = useSelector((s: RootState) => s.ui.breakTypeFilter)

  const { data: breaks = [], isFetching } = useGetBreaksQuery({ type: filter ?? undefined, limit: 50 })
  const [createDispute, { isLoading: creating }] = useCreateDisputeMutation()

  const raise = async (b: BreakView) => {
    try {
      const d = await createDispute({ breakId: b.id }).unwrap()
      dispatch(showToast({ message: `Claim raised for ${rupees(d.claimedPaise)}`, severity: 'success' }))
      navigate(`/claims/${d.id}`)
    } catch (e: unknown) {
      // The backend refuses undercharges with 422 and a readable reason.
      const err = e as { data?: { message?: string } }
      dispatch(showToast({ message: err.data?.message ?? 'Could not raise claim', severity: 'error' }))
    }
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h5">Break queue</Typography>
        <Typography variant="body2" color="text.secondary">
          Largest recoverable first. Only overcharges can be claimed.
        </Typography>
      </Box>

      <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
        <Chip
          label="All" clickable
          color={filter === null ? 'primary' : 'default'}
          variant={filter === null ? 'filled' : 'outlined'}
          onClick={() => dispatch(setBreakTypeFilter(null))}
        />
        {TYPES.map((t) => (
          <Chip
            key={t} clickable label={t.replace(/_/g, ' ').toLowerCase()}
            color={filter === t ? BREAK_COLOR[t] : 'default'}
            variant={filter === t ? 'filled' : 'outlined'}
            onClick={() => dispatch(setBreakTypeFilter(filter === t ? null : t))}
          />
        ))}
        {isFetching && <CircularProgress size={18} sx={{ ml: 1 }} />}
      </Stack>

      <Paper>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Transaction</TableCell>
                <TableCell>Type</TableCell>
                <TableCell align="right">Amount</TableCell>
                <TableCell align="right">Expected</TableCell>
                <TableCell align="right">Charged</TableCell>
                <TableCell align="right">Difference</TableCell>
                <TableCell align="right">Claim</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {breaks.map((b, i) => (
                <MotionRow
                  key={b.id} hover
                  initial={{ opacity: 0, y: 6 }}
                  animate={{ opacity: 1, y: 0 }}
                  /* Staggered, but capped so row 50 does not wait a second. */
                  transition={{ duration: 0.22, delay: Math.min(i, 12) * 0.022 }}
                >
                  <TableCell sx={{ fontFamily: 'ui-monospace, monospace', fontSize: 12 }}>
                    {b.externalTxnId}
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                      {b.payeeCategory.toLowerCase().replace(/_/g, ' ')} · {b.paymentRail.toLowerCase()}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Chip size="small" color={BREAK_COLOR[b.breakType]}
                          label={b.breakType.replace(/_/g, ' ').toLowerCase()} />
                  </TableCell>
                  <TableCell align="right">{rupees(b.amountPaise)}</TableCell>
                  <TableCell align="right" sx={{ color: 'text.secondary' }}>
                    {rupees(b.expectedMdrPaise)}
                  </TableCell>
                  <TableCell align="right">{rupees(b.chargedMdrPaise)}</TableCell>
                  <TableCell align="right"
                             sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums',
                                   color: b.deltaPaise > 0 ? 'error.main' : 'text.secondary' }}>
                    {b.deltaPaise > 0 ? '+' : ''}{rupees(b.deltaPaise)}
                  </TableCell>
                  <TableCell align="right">
                    {b.disputeId ? (
                      <Chip size="small" clickable
                            color={STATUS_COLOR[b.disputeStatus ?? 'DRAFT']}
                            label={b.disputeStatus?.toLowerCase()}
                            onClick={() => navigate(`/claims/${b.disputeId}`)} />
                    ) : b.deltaPaise > 0 ? (
                      <Button size="small" variant="outlined" disabled={creating}
                              startIcon={<AddCircleIcon />} onClick={() => raise(b)}>
                        Claim
                      </Button>
                    ) : (
                      <Tooltip title="The PSP charged too little. There is nothing to recover.">
                        <span>
                          <Button size="small" disabled>Not claimable</Button>
                        </span>
                      </Tooltip>
                    )}
                  </TableCell>
                </MotionRow>
              ))}

              {!isFetching && breaks.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} align="center" sx={{ py: 6, color: 'text.secondary' }}>
                    No breaks. Ingest a settlement file and run a reconciliation.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Paper>
    </Stack>
  )
}
