import { Alert, Snackbar } from '@mui/material'
import { useDispatch, useSelector } from 'react-redux'
import type { RootState } from '../store'
import { clearToast } from '../store/uiSlice'

export default function Toast() {
  const dispatch = useDispatch()
  const toast = useSelector((s: RootState) => s.ui.toast)

  return (
    <Snackbar
      open={!!toast} autoHideDuration={6000}
      onClose={() => dispatch(clearToast())}
      anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
    >
      <Alert severity={toast?.severity ?? 'success'} variant="filled"
             onClose={() => dispatch(clearToast())} sx={{ maxWidth: 560 }}>
        {toast?.message}
      </Alert>
    </Snackbar>
  )
}
