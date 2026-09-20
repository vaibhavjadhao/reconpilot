import { createTheme } from '@mui/material/styles'

/**
 * One theme, defined once.
 *
 * Every colour, radius and font choice lives here rather than in component
 * styles. That is the whole reason to use a design system: consistency is a
 * property of the configuration, not of each developer's discipline.
 */
export const theme = createTheme({
  palette: {
    mode: 'dark',
    background: { default: '#0B1020', paper: '#141B2F' },
    primary:   { main: '#4DD0E1' },
    secondary: { main: '#FFB74D' },
    success:   { main: '#66BB6A' },
    error:     { main: '#EF5350' },
    warning:   { main: '#FFA726' },
    info:      { main: '#64B5F6' },
    divider:   'rgba(255,255,255,0.08)',
    text: { primary: '#E8EDF7', secondary: 'rgba(232,237,247,0.62)' },
  },
  shape: { borderRadius: 14 },
  typography: {
    fontFamily: '"Inter", ui-sans-serif, system-ui, -apple-system, sans-serif',
    h5: { fontWeight: 650, letterSpacing: -0.4 },
    h6: { fontWeight: 620, letterSpacing: -0.2 },
    subtitle2: { fontWeight: 600 },
    // Money and ids read better in a monospace face.
    body2: { fontSize: '0.875rem' },
  },
  components: {
    MuiPaper: {
      styleOverrides: {
        root: { backgroundImage: 'none', border: '1px solid rgba(255,255,255,0.06)' },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: { root: { textTransform: 'none', fontWeight: 600 } },
    },
    MuiChip: { styleOverrides: { root: { fontWeight: 600 } } },
    MuiTableCell: {
      styleOverrides: { head: { fontWeight: 700, color: 'rgba(232,237,247,0.62)' } },
    },
  },
})

/** One colour per break type, used everywhere so the mapping is learnable. */
export const BREAK_COLOR: Record<string, 'error' | 'warning' | 'info' | 'default'> = {
  CHARGED_WHEN_EXEMPT: 'error',
  CAP_BREACHED:        'warning',
  OVERCHARGED:         'info',
  UNDERCHARGED:        'default',
}

export const STATUS_COLOR: Record<string, 'default' | 'info' | 'warning' | 'success' | 'error'> = {
  DRAFT: 'default', FILED: 'info', ACKNOWLEDGED: 'info',
  ACCEPTED: 'warning', RECOVERED: 'success',
  REJECTED: 'error', WITHDRAWN: 'default',
}
