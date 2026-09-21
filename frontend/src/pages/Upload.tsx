import { useCallback, useRef, useState } from 'react'
import {
  Alert, Box, Button, Chip, CircularProgress, LinearProgress, Paper, Stack,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material'
import CloudUploadIcon from '@mui/icons-material/CloudUploadOutlined'
import PlayArrowIcon from '@mui/icons-material/PlayArrowRounded'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { useDispatch } from 'react-redux'
import {
  useGetBatchesQuery, useReconcileBatchMutation, useUploadSettlementFileMutation,
} from '../store/api'
import { showToast } from '../store/uiSlice'
import type { BatchStatusValue, BatchView } from '../types'

const STATUS_COLOR: Record<BatchStatusValue, 'default' | 'info' | 'success' | 'error'> = {
  RECEIVED: 'default',
  PARSING:  'info',
  PARSED:   'success',
  FAILED:   'error',
}

/** A batch in one of these states is still moving, so the list must keep asking. */
const IN_FLIGHT: BatchStatusValue[] = ['RECEIVED', 'PARSING']

export default function Upload() {
  const dispatch = useDispatch()
  const navigate = useNavigate()
  const inputRef = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)

  const [upload, { isLoading: uploading }] = useUploadSettlementFileMutation()
  const [reconcile, { isLoading: reconciling }] = useReconcileBatchMutation()

  // Ingestion is asynchronous by design (ADR 0008): the POST returns 202 and a
  // worker does the reading. So the only way to know what happened is to ask
  // again. Polling stops as soon as nothing is in flight -- a screen that polls
  // forever is a screen that keeps a laptop awake.
  const { data: batches = [], isLoading } = useGetBatchesQuery(undefined, {
    pollingInterval: 2000,
    skipPollingIfUnfocused: true,
  })
  const anyInFlight = batches.some((b) => IN_FLIGHT.includes(b.status))

  const send = useCallback(async (file: File) => {
    try {
      const res = await upload(file).unwrap()
      dispatch(showToast({
        // alreadySeen is not an error. The same file content uploaded twice is
        // recognised and ignored rather than double-counted, which is what
        // makes a retry after a timeout safe.
        message: res.alreadySeen
          ? `${file.name} was already ingested — nothing double-counted`
          : `${file.name} accepted, parsing now`,
        severity: 'success',
      }))
    } catch (e: unknown) {
      const err = e as { data?: { message?: string }; status?: number }
      dispatch(showToast({
        message: err.status === 503
          ? 'The ingestion queue is full. Try again shortly.'   // backpressure, not a crash
          : err.data?.message ?? 'Upload failed',
        severity: 'error',
      }))
    }
  }, [upload, dispatch])

  const run = async (b: BatchView) => {
    try {
      await reconcile(b.batchId).unwrap()
      dispatch(showToast({ message: 'Reconciliation queued', severity: 'success' }))
      // Queued, not done -- give the worker a moment before showing the results.
      setTimeout(() => navigate('/breaks'), 1200)
    } catch {
      dispatch(showToast({ message: 'Could not start reconciliation', severity: 'error' }))
    }
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h5">Settlement files</Typography>
        <Typography variant="body2" color="text.secondary">
          Upload what your PSP charged you. Every row is recomputed from the
          published rules and compared against what was actually taken.
        </Typography>
      </Box>

      <motion.div whileHover={{ scale: 1.005 }} transition={{ duration: 0.2 }}>
        <Paper
          variant="outlined"
          onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => {
            e.preventDefault()
            setDragging(false)
            const f = e.dataTransfer.files?.[0]
            if (f) void send(f)
          }}
          onClick={() => inputRef.current?.click()}
          sx={{
            p: 6, textAlign: 'center', cursor: 'pointer',
            borderStyle: 'dashed', borderWidth: 2,
            borderColor: dragging ? 'primary.main' : 'divider',
            bgcolor: dragging ? 'action.hover' : 'transparent',
            transition: 'border-color .2s, background-color .2s',
          }}
        >
          <CloudUploadIcon sx={{ fontSize: 48, color: 'primary.main', mb: 1 }} />
          <Typography variant="h6">
            {uploading ? 'Uploading…' : 'Drop a settlement CSV here'}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            or click to choose a file — up to 2&nbsp;GB
          </Typography>
          <Typography variant="caption" color="text.secondary"
                      sx={{ display: 'block', mt: 2, fontFamily: 'monospace' }}>
            txn_id, merchant_vpa, amount_paise, txn_type, rail, payee_category,
            charged_mdr_paise, occurred_at
          </Typography>
          <input
            ref={inputRef} type="file" accept=".csv,text/csv" hidden
            onChange={(e) => {
              const f = e.target.files?.[0]
              if (f) void send(f)
              // Reset, or choosing the same file twice fires no change event
              // and the second upload silently never happens.
              e.target.value = ''
            }}
          />
        </Paper>
      </motion.div>

      {uploading && <LinearProgress />}

      <Alert severity="info" variant="outlined">
        The upload returns <strong>202 Accepted</strong> — the file is staged and
        queued, never parsed inside the request. A 2&nbsp;GB file would otherwise
        hold an HTTP connection open long past any proxy timeout.
      </Alert>

      <Box>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
          <Typography variant="h6">Recent batches</Typography>
          {anyInFlight && <CircularProgress size={16} />}
        </Stack>

        {isLoading ? <CircularProgress /> : batches.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            Nothing uploaded yet.
          </Typography>
        ) : (
          <TableContainer component={Paper} variant="outlined">
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>File</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell align="right">Rows</TableCell>
                  <TableCell>Received</TableCell>
                  <TableCell align="right">Action</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {batches.map((b) => (
                  <TableRow key={b.batchId} hover>
                    <TableCell sx={{ fontFamily: 'monospace' }}>{b.sourceName}</TableCell>
                    <TableCell>
                      <Chip size="small" label={b.status.toLowerCase()}
                            color={STATUS_COLOR[b.status]} variant="outlined" />
                      {b.errorMessage && (
                        <Typography variant="caption" color="error"
                                    sx={{ display: 'block', mt: .5 }}>
                          {b.errorMessage}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell align="right">
                      {b.rowCount?.toLocaleString('en-IN') ?? '—'}
                    </TableCell>
                    <TableCell>
                      {new Date(b.receivedAt).toLocaleString('en-IN')}
                    </TableCell>
                    <TableCell align="right">
                      <Button
                        size="small" variant="contained" startIcon={<PlayArrowIcon />}
                        // Only a parsed batch has rows to reconcile. The backend
                        // would reject the rest anyway; this just avoids
                        // offering a button that cannot work.
                        disabled={b.status !== 'PARSED' || reconciling}
                        onClick={() => void run(b)}
                      >
                        Reconcile
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Box>
    </Stack>
  )
}
