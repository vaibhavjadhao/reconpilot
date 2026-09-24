# React, Redux and TypeScript

**Why this matters to you specifically.** You chose React over Angular for this
project even though Angular is your stronger skill, because most 30 LPA+ Java
full-stack job descriptions in India ask for React. That decision only pays off
if you can talk about React with the same confidence you have about Angular —
and the frontend half of a full-stack interview is where Java developers
usually get thin.

All examples are from ReconPilot's console.

---

## Table of contents

1. [How React actually works](#1-how-react-actually-works)
2. [JSX and rendering](#2-jsx-and-rendering)
3. [Components and props](#3-components-and-props)
4. [useState and the rules of state](#4-usestate-and-the-rules-of-state)
5. [useEffect — and why you probably don't need it](#5-useeffect--and-why-you-probably-dont-need-it)
6. [The other hooks](#6-the-other-hooks)
7. [The rules of hooks](#7-the-rules-of-hooks)
8. [Lists, keys and reconciliation](#8-lists-keys-and-reconciliation)
9. [Performance](#9-performance)
10. [TypeScript for React](#10-typescript-for-react)
11. [Redux: the mental model](#11-redux-the-mental-model)
12. [Redux Toolkit](#12-redux-toolkit)
13. [RTK Query](#13-rtk-query)
14. [Server state vs client state](#14-server-state-vs-client-state)
15. [Routing](#15-routing)
16. [Forms and uploads](#16-forms-and-uploads)
17. [Build tooling and deployment](#17-build-tooling-and-deployment)
18. [The traps, collected](#18-the-traps-collected)

---

## 1. How React actually works

### The one-sentence answer

**React is a function from state to UI: you describe what the screen should
look like for a given state, and React works out what to change in the DOM.**

You never write `document.getElementById(...).innerHTML = ...`. You change
state; React re-renders.

### The analogy

Updating the DOM by hand is **editing a printed document with correction
fluid** — find the exact spot, scrape it off, write over it, hope you did not
smudge the line below.

React is **retyping the page and letting a machine diff it against the old
one** and apply only the differences. It sounds wasteful and is in fact faster,
because the diff happens on a cheap in-memory structure and only the genuine
differences touch the (expensive) real DOM.

### The virtual DOM and reconciliation

```
state changes
    │
    ▼
your components run again  →  a new tree of plain objects (the "virtual DOM")
    │
    ▼
React diffs new tree vs old tree      ← "reconciliation"
    │
    ▼
applies the minimum set of real DOM operations
```

**"Re-render" does not mean "touch the DOM".** It means your function runs
again and returns a description. Most re-renders result in no DOM change at all.
This is the single most common misunderstanding.

> **Interview question:** *"Why is the virtual DOM faster than direct DOM
> manipulation?"* — "Strictly speaking it isn't — hand-optimised direct
> manipulation is faster. What the virtual DOM buys is that *correct* updates
> are cheap to write. Real DOM operations trigger layout and paint and are
> expensive, so React batches them and applies only the diff. You get close to
> hand-optimised performance from code that reads like 'render everything'."

---

## 2. JSX and rendering

```tsx
const element = <h1 className="title">Breaks: {count}</h1>;
```

JSX is **not HTML**. It is syntax that compiles to function calls:

```js
React.createElement("h1", { className: "title" }, "Breaks: ", count)
```

Which is why:

- `className` not `class`, `htmlFor` not `for` — `class` and `for` are reserved
  words in JavaScript.
- Attributes are camelCase: `onClick`, `tabIndex`, `strokeWidth`.
- `{}` embeds any JavaScript **expression** (not a statement — no `if`, no
  `for`).
- A component must return **one** root element — hence fragments: `<>...</>`.

### Conditional rendering

```tsx
{isLoading && <Spinner />}                       // render when true
{error ? <Error /> : <Content />}                // either/or
{items.length === 0 && <Empty />}

// ⚠️ THE CLASSIC BUG
{items.length && <List />}      // renders the literal "0" when empty!
```

`0` is falsy but is still a valid React child, so it gets rendered as text.
Always coerce to a boolean: `items.length > 0 && ...`.

---

## 3. Components and props

```tsx
interface StatCardProps {
  label: string
  value: string
  hint?: string              // optional
  onClick?: () => void
}

export default function StatCard({ label, value, hint }: StatCardProps) {
  return (
    <Paper>
      <Typography variant="overline">{label}</Typography>
      <Typography variant="h4">{value}</Typography>
      {hint && <Typography variant="caption">{hint}</Typography>}
    </Paper>
  )
}
```

### Props are read-only

```tsx
function Bad({ items }: Props) {
  items.push("x")        // ☠️ mutating a prop — never do this
}
```

Data flows **down**, events flow **up**. A child that needs to change something
calls a function the parent gave it.

```tsx
<BreakRow break={b} onClaim={(id) => raiseClaim(id)} />
```

### children

```tsx
function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return <section><h2>{title}</h2>{children}</section>
}

<Panel title="Breaks"><BreakTable /></Panel>
```

---

## 4. useState and the rules of state

```tsx
const [dragging, setDragging] = useState(false)
const [file, setFile] = useState<File | null>(null)
```

### State updates are asynchronous and batched

```tsx
const [count, setCount] = useState(0)

function handleClick() {
  setCount(count + 1)
  setCount(count + 1)
  setCount(count + 1)
  // count is 1, not 3 — all three read the same stale `count` from this render
}
```

**Use the functional form when the new value depends on the old:**

```tsx
setCount(c => c + 1)
setCount(c => c + 1)
setCount(c => c + 1)     // now it is 3 ✅
```

React batches updates within an event handler and re-renders once.

### State is immutable — always create a new object

```tsx
// ❌ React compares by reference; the reference did not change, so no re-render
items.push(newItem)
setItems(items)

user.name = "new"
setUser(user)

// ✅ new array / new object
setItems([...items, newItem])
setItems(items.filter(i => i.id !== id))
setItems(items.map(i => i.id === id ? { ...i, done: true } : i))
setUser({ ...user, name: "new" })
```

**This is the most common React bug for backend developers.** Java habits say
mutate; React says replace. React decides whether to re-render with
`Object.is(oldState, newState)` — mutating in place leaves the reference
identical, so nothing happens and you are left staring at correct data that will
not appear.

### Lazy initial state

```tsx
const [data, setData] = useState(expensiveComputation())      // runs EVERY render
const [data, setData] = useState(() => expensiveComputation()) // runs once ✅
```

### Lifting state up

When two siblings need the same state, move it to their nearest common parent
and pass it down. When that parent is many levels up, use Context or a store —
see Redux below.

---

## 5. useEffect — and why you probably don't need it

```tsx
useEffect(() => {
  const id = setInterval(tick, 1000)
  return () => clearInterval(id)      // CLEANUP — runs before the next effect
}, [])                                 // dependency array
```

### The dependency array

```tsx
useEffect(fn)              // after EVERY render — almost always a mistake
useEffect(fn, [])          // once, after the first render
useEffect(fn, [a, b])      // when a or b changes (compared with Object.is)
```

### Cleanup matters

The returned function runs before the effect re-runs, and on unmount. Without
it you leak intervals, subscriptions and listeners — and you get the classic
"setting state on an unmounted component" race.

```tsx
useEffect(() => {
  let cancelled = false
  fetch(url).then(r => r.json()).then(d => { if (!cancelled) setData(d) })
  return () => { cancelled = true }     // ignore a response that lost the race
}, [url])
```

### You probably don't need an effect

This is the modern React position and a strong thing to know:

```tsx
// ❌ derived state in an effect — an extra render and a chance to desync
const [total, setTotal] = useState(0)
useEffect(() => { setTotal(items.reduce((s, i) => s + i.value, 0)) }, [items])

// ✅ just compute it during render
const total = items.reduce((s, i) => s + i.value, 0)
```

```tsx
// ❌ an effect to respond to a user action
useEffect(() => { if (submitted) postForm() }, [submitted])

// ✅ do it in the handler
function handleSubmit() { postForm() }
```

**Effects are for synchronising with something *outside* React** — the DOM,
a timer, a subscription, a browser API. Data fetching technically qualifies,
but a library (RTK Query, TanStack Query) handles caching, deduplication and
invalidation that hand-rolled effects will not.

> **Interview question:** *"When do you use `useEffect`?"* — "For
> synchronisation with an external system: subscriptions, timers, imperative
> DOM work, analytics. Not for derived state — that should be computed during
> render — and not for responding to events, which belongs in the handler. In
> this project I have almost no effects, because server state is owned by RTK
> Query and derived values are computed inline."

### StrictMode double-invocation

In development, React 18+ deliberately runs effects **twice** (mount, unmount,
mount) to surface missing cleanup. It is not a bug, and it does not happen in
production — but if something breaks under it, you have a real cleanup bug.

---

## 6. The other hooks

### useMemo — cache a value

```tsx
const sorted = useMemo(
  () => [...breaks].sort((a, b) => b.deltaPaise - a.deltaPaise),
  [breaks]
)
```

### useCallback — cache a function identity

```tsx
const send = useCallback(async (file: File) => { ... }, [upload, dispatch])
```

Every render creates new function objects. If you pass one to a `React.memo`
child, the child re-renders every time because the prop "changed".
`useCallback` keeps the identity stable.

> **Do not sprinkle these everywhere.** They have a cost — memory, plus the
> comparison itself. Use them when you have measured a problem, when the
> computation is genuinely expensive, or when a stable identity is required by
> a dependency array or a memoised child.

### useRef — a mutable box that does not trigger re-renders

```tsx
const inputRef = useRef<HTMLInputElement>(null)
<input ref={inputRef} type="file" hidden />
inputRef.current?.click()          // imperative escape hatch
```

Two uses: reaching a DOM node, and holding a mutable value across renders
without causing one (a timer id, a previous value).

### useContext — avoid prop drilling

```tsx
const ThemeContext = createContext<Theme>(lightTheme)

<ThemeContext.Provider value={dark}><App /></ThemeContext.Provider>

const theme = useContext(ThemeContext)
```

> ⚠️ **Every consumer re-renders when the context value changes**, and an object
> literal as the value creates a new reference every render. Memoise it. Context
> is for low-frequency values — theme, locale, the current user — not for
> rapidly-changing state. That is what a store is for.

### useReducer — for complex state transitions

```tsx
const [state, dispatch] = useReducer(reducer, initialState)
dispatch({ type: 'submitted' })
```

Better than several `useState`s when the next state depends on the current one
in non-trivial ways — a form wizard, a state machine.

---

## 7. The rules of hooks

**1. Only call hooks at the top level.** Never in a condition, loop, or nested
function.

```tsx
// ❌
if (isLoggedIn) { const [x, setX] = useState(0) }

// ✅
const [x, setX] = useState(0)
if (isLoggedIn) { ... }
```

**2. Only call hooks from React functions** — components or custom hooks.

### Why

React tracks hooks **by call order**, not by name. It keeps an array per
component: the first `useState` is slot 0, the second is slot 1. Put one behind
an `if` and on a later render the order shifts — slot 0 now holds a different
hook's state, and you get bizarre, hard-to-trace bugs.

That single fact explains both rules, and it is the answer interviewers are
listening for.

### Custom hooks

```tsx
function useDebounced<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(id)
  }, [value, delay])
  return debounced
}
```

A custom hook is **just a function starting with `use` that calls other hooks**.
It shares *logic*, not state — two components using it get independent state.

---

## 8. Lists, keys and reconciliation

```tsx
{batches.map(b => (
  <TableRow key={b.batchId}>      // ← stable, unique, from the data
    <TableCell>{b.sourceName}</TableCell>
  </TableRow>
))}
```

### Why the index is the wrong key

```tsx
{items.map((item, i) => <Row key={i} />)}     // ⚠️
```

Keys tell React **which element is which** between renders. With index keys,
deleting the first item shifts every other item's key, so React thinks every
row changed — it reuses the wrong DOM nodes, and any internal state (a focused
input, a checkbox, a CSS animation) attaches to the wrong row.

**Index keys are acceptable only when the list never reorders, never has
insertions or deletions, and items have no state.** In practice: use a real id.

---

## 9. Performance

### Measure first

React DevTools → Profiler → record an interaction. It shows which components
rendered and why. **Optimise what you measured, not what you suspect.**

### The tools

```tsx
// 1. React.memo — skip re-render when props are shallowly equal
const BreakRow = React.memo(function BreakRow({ item }: Props) { ... })

// 2. Stable identities, so memo actually works
const onClaim = useCallback((id: string) => claim(id), [claim])

// 3. Do not create objects in JSX props
<Child style={{ margin: 8 }} />          // new object every render
const style = useMemo(() => ({ margin: 8 }), [])   // or hoist it outside

// 4. Code splitting
const ClaimDetail = lazy(() => import('./pages/ClaimDetail'))
<Suspense fallback={<Spinner />}><ClaimDetail /></Suspense>
```

**`React.memo` without stable props does nothing** — a new function or object
prop defeats it every time, and you have paid for the comparison for nothing.

### Long lists

Rendering ten thousand rows creates ten thousand DOM nodes. **Virtualise**
(`react-window`, `@tanstack/react-virtual`) so only the visible rows exist. Or
do what ReconPilot does and paginate server-side — the backend already sorts
largest-recoverable-first and returns 50.

### Bundle size

ReconPilot's build warns at 1.1 MB. Fixes, in order of effect: route-level code
splitting with `lazy`, importing only what you use from large libraries, and
checking with `rollup-plugin-visualizer` before guessing.

---

## 10. TypeScript for React

### Why it earns its place here

The frontend types mirror the backend records, so **a change to the API surfaces
as a compile error rather than as `undefined` at runtime**:

```ts
// mirrors in.reconpilot.ingest.BatchStatus
export interface BatchView {
  batchId: string
  sourceName: string
  status: BatchStatusValue
  rowCount: number | null          // nullable in Java → nullable here
  errorMessage: string | null
}
```

### Union types instead of strings

```ts
export type BreakType =
  | 'CHARGED_WHEN_EXEMPT' | 'CAP_BREACHED' | 'OVERCHARGED' | 'UNDERCHARGED'

// typo caught at COMPILE time
const t: BreakType = 'OVERCHARGE'    // ❌ Error
```

A string union is TypeScript's answer to a Java enum, and it is checked
exhaustively in a `switch` when you help it:

```ts
function label(t: BreakType): string {
  switch (t) {
    case 'CHARGED_WHEN_EXEMPT': return 'charged when exempt'
    case 'CAP_BREACHED':        return 'cap breached'
    case 'OVERCHARGED':         return 'overcharged'
    case 'UNDERCHARGED':        return 'undercharged'
    default: {
      const _exhaustive: never = t     // adding a 5th type breaks the build ✅
      return _exhaustive
    }
  }
}
```

### Record for exhaustive maps

```ts
export const ALLOWED_NEXT: Record<DisputeStatus, DisputeStatus[]> = {
  DRAFT:        ['FILED', 'WITHDRAWN'],
  FILED:        ['ACKNOWLEDGED', 'REJECTED', 'WITHDRAWN'],
  ACKNOWLEDGED: ['ACCEPTED', 'REJECTED', 'WITHDRAWN'],
  ACCEPTED:     ['RECOVERED'],
  RECOVERED:    [],
  REJECTED:     [],
  WITHDRAWN:    [],
}
```

`Record<K, V>` requires **every** key. Add a status to the union and this object
stops compiling until you handle it — the compiler enforcing your state machine.

### Typing components and events

```tsx
function Upload({ onDone }: { onDone: (id: string) => void }) { }

const onChange = (e: React.ChangeEvent<HTMLInputElement>) => e.target.files?.[0]
const onDrop   = (e: React.DragEvent<HTMLDivElement>) => e.dataTransfer.files?.[0]
const onClick  = (e: React.MouseEvent<HTMLButtonElement>) => e.preventDefault()
```

### The essentials

```ts
type A = { id: string } & { name: string }     // intersection (AND)
type B = string | number                        // union (OR)
type C = Partial<BatchView>                     // all optional
type D = Pick<BatchView, 'batchId' | 'status'>
type E = Omit<BatchView, 'errorMessage'>
type F = ReturnType<typeof makeStore>           // infer from a value

const x = y as string                           // assertion — you are overriding
const z = y!                                    // non-null assertion — same risk
```

> **`as` and `!` are you telling the compiler to stop checking.** Each one is a
> place a runtime error can appear. Use them when you genuinely know more than
> the compiler — parsing a JSON response, for instance — and treat each as a
> small debt.

`unknown` over `any`: `any` disables checking entirely and spreads; `unknown`
forces you to narrow before use.

---

## 11. Redux: the mental model

### The problem it solves

Several distant components need the same data. Passing it down through six
layers ("prop drilling") is miserable, and lifting state to the root makes the
root a dumping ground.

### The idea

**One store. Components read from it. To change it, dispatch an action; a pure
reducer computes the next state.**

```
  Component
     │ dispatch(action)
     ▼
  Reducer  (pure: (state, action) => newState)
     │
     ▼
   Store ──── subscribed components re-render
```

### The three principles

1. **Single source of truth** — one store object.
2. **State is read-only** — the only way to change it is to dispatch an action.
3. **Changes are made by pure functions** — reducers take state and an action
   and return new state, with no side effects and no mutation.

Pure reducers are what make time-travel debugging and the DevTools possible: the
same state and action always produce the same result.

### Do you even need Redux?

Honest answer: **often not.** For local UI state, `useState`. For server data,
a query library. Redux earns its place when you have genuinely shared *client*
state that many distant components read and write — and in this project that is
the auth token, the toast, and a filter.

Saying that in an interview reads far better than defending Redux everywhere.

---

## 12. Redux Toolkit

Modern Redux. Nobody should write the old boilerplate.

```ts
const uiSlice = createSlice({
  name: 'ui',
  initialState: { breakTypeFilter: null as string | null, toast: null as Toast | null },
  reducers: {
    setBreakTypeFilter(state, action: PayloadAction<string | null>) {
      state.breakTypeFilter = action.payload      // looks like mutation!
    },
    showToast(state, action: PayloadAction<Toast>) {
      state.toast = action.payload
    },
    clearToast(state) { state.toast = null },
  },
})

export const { setBreakTypeFilter, showToast, clearToast } = uiSlice.actions
export default uiSlice.reducer
```

### Why "mutation" is allowed here

**`createSlice` wraps reducers in Immer.** You mutate a *draft* proxy; Immer
records the changes and produces a new immutable state. The rule about never
mutating still holds — Immer is doing the copying for you.

One catch:

```ts
// ✅ mutate the draft
state.items.push(item)

// ✅ or return a new value
return { ...state, items: [...state.items, item] }

// ❌ never both in one reducer
state.items.push(item)
return newState        // Immer throws
```

### The store

```ts
export const store = configureStore({
  reducer: {
    [api.reducerPath]: api.reducer,
    ui: uiReducer,
    auth: authReducer,
  },
  middleware: (getDefault) => getDefault().concat(api.middleware),
})

export type RootState = ReturnType<typeof store.getState>
export type AppDispatch = typeof store.dispatch
```

`configureStore` gives you the Redux DevTools, thunks, and development-time
checks for accidental mutation and non-serialisable values, with no setup.

### Reading and writing from components

```tsx
const filter = useSelector((s: RootState) => s.ui.breakTypeFilter)
const dispatch = useDispatch()
dispatch(setBreakTypeFilter('OVERCHARGED'))
```

> **Selector performance:** `useSelector` re-renders when the selected value
> changes by reference. Returning a new object or array each time
> (`s => ({ a: s.a })`, or `s => s.items.filter(...)`) re-renders on **every**
> store change. Select primitives, or memoise with `createSelector`.

---

## 13. RTK Query

The part that removed most of the code.

```ts
export const api = createApi({
  reducerPath: 'api',
  baseQuery: baseQueryWithAuth,
  tagTypes: ['Break', 'Dispute', 'Batch'],
  endpoints: (build) => ({

    getBreaks: build.query<BreakView[], { type?: string; limit?: number }>({
      query: ({ type, limit = 50 }) => ({ url: '/breaks', params: { type, limit } }),
      providesTags: ['Break'],
    }),

    createDispute: build.mutation<DisputeView, { breakId: string }>({
      query: ({ breakId }) => ({ url: '/disputes', method: 'POST', params: { breakId } }),
      invalidatesTags: ['Break', 'Dispute'],     // ← refetch happens automatically
    }),
  }),
})

export const { useGetBreaksQuery, useCreateDisputeMutation } = api
```

```tsx
const { data = [], isLoading, isFetching, error } = useGetBreaksQuery({ limit: 50 })
const [createDispute, { isLoading: creating }] = useCreateDisputeMutation()
```

**You get for free:** caching, request deduplication, loading and error state,
refetch on focus and reconnect, and automatic invalidation.

### Tags are the good idea

`providesTags` labels what a query's data *is*. `invalidatesTags` says what a
mutation *affects*. Raise a claim and every query tagged `Break` refetches —
without any component knowing about any other component.

### Auth in one place

```ts
const rawBaseQuery = fetchBaseQuery({
  baseUrl: '/api',
  prepareHeaders: (headers, { getState }) => {
    const token = (getState() as RootState).auth.token
    if (token) headers.set('Authorization', `Bearer ${token}`)
    return headers
  },
})

// wrap it so ANY 401 signs the user out centrally
const baseQueryWithAuth: BaseQueryFn = async (args, api, extra) => {
  const result = await rawBaseQuery(args, api, extra)
  if (result.error?.status === 401) api.dispatch(signedOut())
  return result
}
```

> **Why this matters:** a JWT cannot be un-issued, so the first sign that it has
> expired is a 401 from an ordinary call. Handling it in the base query means
> every screen gets the behaviour, instead of each one inventing its own.

### Polling, stopped deliberately

```tsx
const { data: batches = [] } = useGetBatchesQuery(undefined, {
  pollingInterval: 2000,
  skipPollingIfUnfocused: true,
})
```

ReconPilot polls the batch list while an upload is parsing — because ingestion
is asynchronous, the only way to learn the outcome is to ask again. It stops
when nothing is in flight: **a screen that polls forever is a screen that keeps
a laptop awake.**

---

## 14. Server state vs client state

The distinction that makes a frontend clean:

| | Server state | Client state |
|---|---|---|
| Owned by | the backend | this browser tab |
| Can go stale | ✅ | ❌ |
| Examples | breaks, batches, disputes | a filter, a modal, a draft, the toast |
| Tool | **RTK Query** | **`useState` or a Redux slice** |

Mixing them is how caches go stale and filters get lost. ReconPilot's `uiSlice`
says so in a comment, because it is the kind of boundary that erodes silently.

---

## 15. Routing

```tsx
<BrowserRouter>
  <Routes>
    <Route element={<Layout />}>            {/* shared chrome */}
      <Route index element={<Dashboard />} />
      <Route path="upload" element={<Upload />} />
      <Route path="breaks" element={<Breaks />} />
      <Route path="claims/:id" element={<ClaimDetail />} />
    </Route>
  </Routes>
</BrowserRouter>
```

```tsx
const { id } = useParams()
const navigate = useNavigate()
navigate('/breaks')
const [params, setParams] = useSearchParams()
```

`<Layout>` renders `<Outlet />` where the child route goes.

### The deployment trap — deep links 404

A single-page app owns its own routes. Ask the server for `/claims/abc-123` and
it looks for a file at that path and returns **404**. Every deep link and every
page refresh away from `/` is broken.

```nginx
location / {
    try_files $uri $uri/ /index.html;    # serve index.html; React Router resolves it
}
```

**This is a real interview question for full-stack roles**, and it catches
people who have only ever run `npm run dev`.

---

## 16. Forms and uploads

### Controlled inputs

```tsx
const [email, setEmail] = useState('')
<input value={email} onChange={e => setEmail(e.target.value)} />
```

React owns the value. Uncontrolled inputs (via `ref`) are fine for
fire-and-forget fields such as a file picker.

### File upload — the header trap

```ts
uploadSettlementFile: build.mutation<IngestionSubmission, File>({
  query: (file) => {
    const form = new FormData()
    form.append('file', file)
    return { url: '/ingest', method: 'POST', body: form }
  },
}),
```

**Do not set `Content-Type` yourself.** A multipart header carries a *boundary
string* that only the browser knows:

```
Content-Type: multipart/form-data; boundary=----WebKitFormBoundaryX3k9
```

Set it by hand and you send a header with no boundary, the server cannot parse
the body, and you get a 400 that reads like a server bug. Let the browser write
it.

### Resetting a file input

```tsx
onChange={(e) => {
  const f = e.target.files?.[0]
  if (f) void send(f)
  e.target.value = ''     // or choosing the SAME file twice fires no change event
}}
```

A genuinely obscure bug: without the reset, re-selecting the same file appears
to do nothing.

---

## 17. Build tooling and deployment

### Vite

```ts
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': 'http://localhost:8080' },   // ← removes CORS in development
  },
})
```

**The dev proxy is why this project never configures CORS.** The browser sees
one origin — the Vite dev server — which forwards `/api` to Spring. In
production nginx does the same job. **One origin means CORS never enters the
picture**, in either environment.

> **Interview question:** *"How did you handle CORS?"* — "I didn't have to. In
> development Vite proxies `/api` to the backend; in production nginx proxies
> the same path to the backend container. The browser only ever talks to one
> origin, so there is no cross-origin request to permit. CORS would only be
> needed if the frontend were served from a different origin than the API,
> which is a deployment choice rather than an inevitability."

### The production image

```dockerfile
FROM node:24-alpine AS build
COPY package.json package-lock.json* ./
RUN npm ci                      # exactly the lockfile — reproducible
COPY . .
RUN npm run build               # tsc -b && vite build

FROM nginx:1.27-alpine AS runtime
COPY --from=build /build/dist /usr/share/nginx/html
```

The output is static files. **No Node at runtime**, because nothing needs it —
77.5 MB.

### Cache headers

```nginx
location /assets/ { expires 1y; add_header Cache-Control "public, immutable"; }
location = /index.html { add_header Cache-Control "no-cache"; }
```

Vite gives assets content-hashed filenames, so they can be cached forever.
`index.html` must **not** be, or a browser keeps serving a stale page that
references assets the new deploy has deleted.

---

## 18. The traps, collected

| # | Trap | The answer |
|---|---|---|
| 1 | Mutating state directly | React compares by reference; create a new object |
| 2 | `setCount(count + 1)` twice | Both read stale state; use `setCount(c => c+1)` |
| 3 | `{items.length && <X/>}` | Renders "0"; use `length > 0` |
| 4 | Index as a list key | Wrong DOM reuse on reorder/delete |
| 5 | Hook inside a condition | Hooks are tracked by call order |
| 6 | Missing effect dependencies | Stale closures; trust the lint rule |
| 7 | Effect without cleanup | Leaked timers and subscriptions |
| 8 | Effect for derived state | Compute during render instead |
| 9 | `React.memo` with unstable props | Does nothing; memoise the props too |
| 10 | Object literal as a context value | New reference each render; all consumers re-render |
| 11 | Selector returning a new object | Re-renders on every store change |
| 12 | Setting `Content-Type` on FormData | Loses the multipart boundary |
| 13 | Not resetting a file input | Same file twice fires no event |
| 14 | No `try_files` in nginx | Deep links 404 |
| 15 | Caching `index.html` | Stale page references deleted assets |
| 16 | `npm install` in CI or Docker | Not reproducible; use `npm ci` |
| 17 | `any` everywhere | Disables the checker you installed on purpose |
| 18 | Redux for everything | Server state belongs in a query cache |
| 19 | Polling forever | Stop when nothing is in flight |
| 20 | Assuming re-render = DOM update | It means your function ran again |

---

## What to do next

1. **Open the Profiler** in React DevTools, upload a file in ReconPilot, and
   watch which components render while the batch list polls. Then add
   `React.memo` to a row and watch the difference.
2. **Break a key.** Change `key={b.batchId}` to `key={i}`, add a row at the
   top, and watch the wrong row keep its state.
3. **Delete the Vite proxy** and watch every request fail with a CORS error.
   That is the clearest possible explanation of what the proxy was doing.

Next: `09-javascript.md`.
