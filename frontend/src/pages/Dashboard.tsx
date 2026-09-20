import { Box, Paper, Stack, Typography } from '@mui/material'
import TrendingUpIcon from '@mui/icons-material/TrendingUp'
import ReportProblemIcon from '@mui/icons-material/ReportProblem'
import GavelIcon from '@mui/icons-material/Gavel'
import PaidIcon from '@mui/icons-material/Paid'
import {
  Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import { useGetBreakSummaryQuery, useGetDisputeSummaryQuery } from '../store/api'
import { rupees } from '../types'
import StatCard from '../components/StatCard'

const CHART_COLOR: Record<string, string> = {
  CHARGED_WHEN_EXEMPT: '#EF5350',
  CAP_BREACHED:        '#FFA726',
  OVERCHARGED:         '#64B5F6',
  UNDERCHARGED:        '#546E7A',
}

export default function Dashboard() {
  const { data: breaks = [], isLoading: bLoading } = useGetBreakSummaryQuery()
  const { data: claims, isLoading: cLoading } = useGetDisputeSummaryQuery()

  const totalBreaks = breaks.reduce((s, r) => s + r.count, 0)
  const recoverable = breaks.reduce((s, r) => s + r.recoverablePaise, 0)

  const chart = breaks
    .filter((r) => r.recoverablePaise > 0)
    .map((r) => ({
      name: r.breakType.replace(/_/g, ' ').toLowerCase(),
      type: r.breakType,
      value: r.recoverablePaise / 100,
      count: r.count,
    }))

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h5">Recovery overview</Typography>
        <Typography variant="body2" color="text.secondary">
          UPI MDR verified against the NPCI framework effective 15 October 2026
        </Typography>
      </Box>

      <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap' }}>
        <StatCard
          label="Breaks found" value={totalBreaks.toLocaleString('en-IN')}
          hint="discrepancies detected" loading={bLoading} delay={0}
          accent="#EF5350" icon={<ReportProblemIcon fontSize="small" sx={{ color: '#EF5350' }} />}
        />
        <StatCard
          label="Recoverable" value={rupees(recoverable)}
          hint="overcharges only" loading={bLoading} delay={0.06}
          accent="#4DD0E1" icon={<TrendingUpIcon fontSize="small" sx={{ color: '#4DD0E1' }} />}
        />
        <StatCard
          label="Claimed" value={rupees(claims?.claimed_paise)}
          hint={`${claims?.total ?? 0} claims raised`} loading={cLoading} delay={0.12}
          accent="#FFB74D" icon={<GavelIcon fontSize="small" sx={{ color: '#FFB74D' }} />}
        />
        <StatCard
          label="Recovered" value={rupees(claims?.recovered_paise)}
          hint={`${claims?.recovered_count ?? 0} settled`} loading={cLoading} delay={0.18}
          accent="#66BB6A" icon={<PaidIcon fontSize="small" sx={{ color: '#66BB6A' }} />}
        />
      </Stack>

      <Paper sx={{ p: 3 }}>
        <Typography variant="subtitle2" sx={{ mb: 0.5 }}>Recoverable by break type</Typography>
        <Typography variant="caption" color="text.secondary">
          Undercharges are excluded: the PSP took too little, so there is nothing to claim.
        </Typography>

        <Box sx={{ height: 300, mt: 2 }}>
          <ResponsiveContainer>
            <BarChart data={chart} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.06)" vertical={false} />
              <XAxis dataKey="name" tick={{ fill: 'rgba(232,237,247,0.62)', fontSize: 12 }}
                     axisLine={false} tickLine={false} />
              <YAxis tick={{ fill: 'rgba(232,237,247,0.62)', fontSize: 12 }}
                     axisLine={false} tickLine={false}
                     tickFormatter={(v) => `₹${(v / 1000).toFixed(0)}k`} />
              <Tooltip
                cursor={{ fill: 'rgba(255,255,255,0.04)' }}
                contentStyle={{ background: '#141B2F', border: '1px solid rgba(255,255,255,0.1)',
                                borderRadius: 12, color: '#E8EDF7' }}
                formatter={((v: number, _n: unknown, p: { payload: { count: number } }) =>
                  [`₹${v.toLocaleString('en-IN')} · ${p.payload.count} breaks`, 'Recoverable']) as never}
              />
              <Bar dataKey="value" radius={[8, 8, 0, 0]} animationDuration={700}>
                {chart.map((d) => <Cell key={d.type} fill={CHART_COLOR[d.type] ?? '#4DD0E1'} />)}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </Box>
      </Paper>
    </Stack>
  )
}
