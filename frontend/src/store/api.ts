import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react'
import type {
  BreakView, BreakSummaryRow, DisputeView, DisputeEventView,
  DisputeSummary, DisputeStatus,
} from '../types'

/**
 * RTK Query: Redux's own data-fetching layer.
 *
 * Declaring endpoints here generates typed hooks, a normalised cache, loading
 * and error state, and automatic refetching. Writing the equivalent by hand in
 * plain Redux means an action, a reducer and three state flags per request,
 * which is most of why hand-rolled Redux feels miserable.
 *
 * `tagTypes` drives invalidation: a mutation declares what it invalidates, and
 * every query holding that tag refetches. So raising a claim updates the break
 * list and the dashboard without either of them knowing a claim was raised.
 */
export const api = createApi({
  reducerPath: 'api',
  baseQuery: fetchBaseQuery({ baseUrl: '/api' }),
  tagTypes: ['Break', 'Dispute'],
  endpoints: (build) => ({

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
      // A new claim changes both the break list and the dashboard.
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
  useGetBreakSummaryQuery,
  useGetBreaksQuery,
  useGetDisputeSummaryQuery,
  useGetDisputeQuery,
  useGetDisputeHistoryQuery,
  useCreateDisputeMutation,
  useTransitionDisputeMutation,
} = api
