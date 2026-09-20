import { Box, Paper, Skeleton, Typography } from '@mui/material'
import { motion } from 'framer-motion'
import type { ReactNode } from 'react'

interface Props {
  label: string
  value: string
  hint?: string
  icon?: ReactNode
  accent?: string
  loading?: boolean
  delay?: number
}

export default function StatCard({ label, value, hint, icon, accent = '#4DD0E1', loading, delay = 0 }: Props) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.34, delay, ease: [0.22, 1, 0.36, 1] }}
      style={{ flex: '1 1 210px', minWidth: 210 }}
    >
      <Paper sx={{ p: 2.5, height: '100%', position: 'relative', overflow: 'hidden' }}>
        {/* A thin accent bar carries the colour without shouting. */}
        <Box sx={{ position: 'absolute', inset: 0, height: 3, bgcolor: accent }} />
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.25 }}>
          {icon}
          <Typography variant="caption" sx={{ color: 'text.secondary', letterSpacing: 0.6 }}>
            {label.toUpperCase()}
          </Typography>
        </Box>

        {loading
          ? <Skeleton width="70%" height={40} />
          : <Typography variant="h5" sx={{ fontVariantNumeric: 'tabular-nums' }}>{value}</Typography>}

        {hint && (
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>{hint}</Typography>
        )}
      </Paper>
    </motion.div>
  )
}
