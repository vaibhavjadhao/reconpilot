// Mirrors the backend records. Kept in one file so a change to the API surfaces
// as a compile error here rather than as undefined at runtime.

export type BreakType =
  | 'CHARGED_WHEN_EXEMPT'
  | 'CAP_BREACHED'
  | 'OVERCHARGED'
  | 'UNDERCHARGED'

export type DisputeStatus =
  | 'DRAFT' | 'FILED' | 'ACKNOWLEDGED' | 'ACCEPTED'
  | 'RECOVERED' | 'REJECTED' | 'WITHDRAWN'

export interface BreakView {
  id: string
  breakType: BreakType
  status: string
  expectedMdrPaise: number
  chargedMdrPaise: number
  deltaPaise: number
  externalTxnId: string
  amountPaise: number
  txnType: string
  paymentRail: string
  payeeCategory: string
  disputeId: string | null
  disputeStatus: DisputeStatus | null
}

export interface BreakSummaryRow {
  breakType: BreakType
  count: number
  netDeltaPaise: number
  recoverablePaise: number
}

export interface DisputeView {
  id: string
  breakId: string
  status: DisputeStatus
  claimedPaise: number
  recoveredPaise: number | null
  externalReference: string | null
  createdAt: string
  filedAt: string | null
  resolvedAt: string | null
}

export interface DisputeEventView {
  fromStatus: DisputeStatus | null
  toStatus: DisputeStatus
  actor: string
  note: string | null
  recoveredPaise: number | null
  occurredAt: string
}

export interface DisputeSummary {
  total: number
  recovered_count: number
  claimed_paise: number
  recovered_paise: number
}

/**
 * Which moves the backend will accept from a given state.
 *
 * Deliberately mirrors DisputeStatus.java. The UI uses it only to decide which
 * buttons to *offer* -- the backend remains the authority and rejects anything
 * illegal with a 409. A UI that hides a button has made it inconvenient, not
 * impossible.
 */
export const ALLOWED_NEXT: Record<DisputeStatus, DisputeStatus[]> = {
  DRAFT:        ['FILED', 'WITHDRAWN'],
  FILED:        ['ACKNOWLEDGED', 'REJECTED', 'WITHDRAWN'],
  ACKNOWLEDGED: ['ACCEPTED', 'REJECTED', 'WITHDRAWN'],
  ACCEPTED:     ['RECOVERED'],
  RECOVERED:    [],
  REJECTED:     [],
  WITHDRAWN:    [],
}

/** Money is integer paise everywhere. Format only at the edge. */
export const rupees = (paise: number | null | undefined): string =>
  paise == null
    ? '—'
    : new Intl.NumberFormat('en-IN', {
        style: 'currency', currency: 'INR', maximumFractionDigits: 2,
      }).format(paise / 100)
