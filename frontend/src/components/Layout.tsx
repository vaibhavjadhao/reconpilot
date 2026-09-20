import { AppBar, Box, Container, Tab, Tabs, Toolbar, Typography, Chip } from '@mui/material'
import InsightsIcon from '@mui/icons-material/Insights'
import { Link, Outlet, useLocation } from 'react-router-dom'
import { motion } from 'framer-motion'

const TABS = [
  { label: 'Dashboard', path: '/' },
  { label: 'Breaks',    path: '/breaks' },
]

export default function Layout() {
  const { pathname } = useLocation()
  const active = pathname.startsWith('/breaks') || pathname.startsWith('/claims') ? 1 : 0

  return (
    <Box sx={{ minHeight: '100dvh', bgcolor: 'background.default' }}>
      <AppBar
        position="sticky"
        elevation={0}
        sx={{ bgcolor: 'rgba(11,16,32,0.82)', backdropFilter: 'blur(12px)',
              borderBottom: '1px solid', borderColor: 'divider' }}
      >
        <Container maxWidth="lg">
          <Toolbar disableGutters sx={{ gap: 2, minHeight: 64 }}>
            <InsightsIcon sx={{ color: 'primary.main' }} />
            <Typography variant="h6" sx={{ mr: 2 }}>ReconPilot</Typography>

            <Tabs value={active} sx={{ minHeight: 64 }}>
              {TABS.map((t) => (
                <Tab key={t.path} component={Link} to={t.path} label={t.label}
                     sx={{ minHeight: 64, textTransform: 'none', fontWeight: 600 }} />
              ))}
            </Tabs>

            <Box sx={{ flex: 1 }} />
            <Chip size="small" variant="outlined" color="warning" label="Filing disabled — D7" />
          </Toolbar>
        </Container>
      </AppBar>

      <Container maxWidth="lg" sx={{ py: 4 }}>
        {/* Keyed on pathname so each route animates in rather than snapping. */}
        <motion.div
          key={pathname}
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
        >
          <Outlet />
        </motion.div>
      </Container>
    </Box>
  )
}
