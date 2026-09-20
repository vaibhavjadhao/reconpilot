import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react'
import type { BaseQueryFn, FetchArgs, FetchBaseQueryError } from '@reduxjs/toolkit/query'
import type {
  BreakView, BreakSummaryRow, DisputeView, DisputeEventView,
  DisputeSummary, DisputeStatus,
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
  tagTypes: ['Break', 'Dispute'],
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
  useRegisterMutation,
  useGetBreakSummaryQuery,
  useGetBreaksQuery,
  useGetDisputeSummaryQuery,
  useGetDisputeQuery,
  useGetDisputeHistoryQuery,
  useCreateDisputeMutation,
  useTransitionDisputeMutation,
} = api
