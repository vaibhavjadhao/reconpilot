import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react'
import type { BaseQueryFn, FetchArgs, FetchBaseQueryError } from '@reduxjs/toolkit/query'
import type {
  BreakView, BreakSummaryRow, DisputeView, DisputeEventView,
  DisputeSummary, DisputeStatus, BatchView, IngestionSubmission,
} from '../types'
import type { RootState } from './index'
import { signedOut } from './authSlice'

const rawBaseQuery = fetchBaseQuery({
  baseUrl: '/api',
  // Attaches the bearer token to every request from one place. Doing it per
  // call is how one endpoint ends up unauthenticated and nobody notices.
  prepareHeaders: (headers, { getState }) => {
    const token = (getState() as RootState).auth.token
    if (token) headers.set('Authorization', `Bearer ${token}`)
    return headers
  },
})

/**
 * Wraps every request so an expired or rejected token signs the user out
 * centrally.
 *
 * A JWT cannot be un-issued, so the first indication that it is no longer good
 * is a 401 from an ordinary call. Handling that here means every screen gets
 * the behaviour, rather than each one inventing its own.
 */
const baseQueryWithAuth: BaseQueryFn<string | FetchArgs, unknown, FetchBaseQueryError> =
  async (args, api, extraOptions) => {
    const result = await rawBaseQuery(args, api, extraOptions)
    if (result.error?.status === 401) {
      api.dispatch(signedOut())
    }
    return result
  }

export const api = createApi({
  reducerPath: 'api',
  baseQuery: baseQueryWithAuth,
  tagTypes: ['Break', 'Dispute', 'Batch'],
  endpoints: (build) => ({

    login: build.mutation<
      { token: string; expiresInSeconds: number; email: string; role: string },
      { email: string; password: string }
    >({
      query: (body) => ({ url: '/auth/login', method: 'POST', body }),
    }),

    register: build.mutation<
      { tenantId: string; email: string },
      { tenantName: string; email: string; password: string }
    >({
      query: (body) => ({ url: '/auth/register', method: 'POST', body }),
    }),

    /**
     * Uploads a settlement file.
     *
     * The body is a FormData and Content-Type is deliberately NOT set: the
     * browser has to write it itself, because a multipart header carries a
     * boundary string only the browser knows. Setting it by hand produces a
     * header with no boundary and a 400 that reads like a server bug.
     *
     * Returns 202, not 200. Nothing has been parsed when this resolves -- the
     * file is staged and queued, and the batch has to be polled.
     */
    uploadSettlementFile: build.mutation<IngestionSubmission, File>({
      query: (file) => {
        const form = new FormData()
        form.append('file', file)
        return { url: '/ingest', method: 'POST', body: form }
      },
      invalidatesTags: ['Batch'],
    }),

    getBatches: build.query<BatchView[], void>({
      query: () => '/ingest',
      providesTags: ['Batch'],
    }),

    reconcileBatch: build.mutation<{ batchId: string; status: string }, string>({
      query: (batchId) => ({ url: `/recon/${batchId}`, method: 'POST' }),
      // Reconciliation writes breaks, so the break views are now stale. It is
      // also queued, so this invalidation is optimistic -- the polling on the
      // upload screen is what actually observes the result.
      invalidatesTags: ['Break', 'Batch'],
    }),

    getBreakSummary: build.query<BreakSummaryRow[], void>({
      query: () => '/breaks/summary',
      providesTags: ['Break'],
    }),

    getBreaks: build.query<BreakView[], { type?: string; limit?: number }>({
      query: ({ type, limit = 50 }) => ({
        url: '/breaks',
        params: { ...(type ? { type } : {}), limit },
      }),
      providesTags: ['Break'],
    }),

    getDisputeSummary: build.query<DisputeSummary, void>({
      query: () => '/disputes/summary',
      providesTags: ['Dispute'],
    }),

    getDispute: build.query<DisputeView, string>({
      query: (id) => `/disputes/${id}`,
      providesTags: ['Dispute'],
    }),

    getDisputeHistory: build.query<DisputeEventView[], string>({
      query: (id) => `/disputes/${id}/history`,
      providesTags: ['Dispute'],
    }),

    createDispute: build.mutation<DisputeView, { breakId: string; actor?: string }>({
      query: ({ breakId, actor = 'analyst' }) => ({
        url: '/disputes', method: 'POST', params: { breakId, actor },
      }),
      invalidatesTags: ['Break', 'Dispute'],
    }),

    transitionDispute: build.mutation<
      DisputeView,
      { id: string; target: DisputeStatus; actor?: string; note?: string; recoveredPaise?: number }
    >({
      query: ({ id, target, actor = 'analyst', note, recoveredPaise }) => ({
        url: `/disputes/${id}/transition`,
        method: 'POST',
        params: {
          target, actor,
          ...(note ? { note } : {}),
          ...(recoveredPaise != null ? { recoveredPaise } : {}),
        },
      }),
      invalidatesTags: ['Break', 'Dispute'],
    }),
  }),
})

export const {
  useLoginMutation,
  useUploadSettlementFileMutation,
  useGetBatchesQuery,
  useReconcileBatchMutation,
  useRegisterMutation,
  useGetBreakSummaryQuery,
  useGetBreaksQuery,
  useGetDisputeSummaryQuery,
  useGetDisputeQuery,
  useGetDisputeHistoryQuery,
  useCreateDisputeMutation,
  useTransitionDisputeMutation,
} = api
