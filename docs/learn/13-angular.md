# Angular

**Read this one differently.** Angular is your *strongest* frontend skill and
it is on your CV, so interviewers will probe it — but ReconPilot is built in
React, so you will also be asked **"you know Angular, why did you choose
React?"**

This document therefore does two jobs: refresh the Angular depth an interviewer
will test, and give you a defensible answer to the comparison question. It
covers modern Angular (standalone components, signals), because saying
"NgModules" in 2026 dates you.

---

## Table of contents

1. [The mental model, versus React](#1-the-mental-model-versus-react)
2. [Components and templates](#2-components-and-templates)
3. [Standalone components](#3-standalone-components)
4. [Data binding and the new control flow](#4-data-binding-and-the-new-control-flow)
5. [Inputs, outputs and component communication](#5-inputs-outputs-and-component-communication)
6. [Dependency injection](#6-dependency-injection)
7. [Change detection — and Zone.js](#7-change-detection--and-zonejs)
8. [Signals](#8-signals)
9. [RxJS, the parts you actually need](#9-rxjs-the-parts-you-actually-need)
10. [HttpClient and interceptors](#10-httpclient-and-interceptors)
11. [Routing and guards](#11-routing-and-guards)
12. [Forms](#12-forms)
13. [Lifecycle hooks](#13-lifecycle-hooks)
14. [Angular vs React: the answer](#14-angular-vs-react-the-answer)
15. [The traps, collected](#15-the-traps-collected)

---

## 1. The mental model, versus React

| | Angular | React |
|---|---|---|
| What it is | a **framework** | a **library** |
| Opinions | many — routing, HTTP, forms, DI all included | few — you assemble them |
| Language | TypeScript, mandatory | optional |
| Templates | separate HTML with its own syntax | JSX — JavaScript |
| DI | first-class, hierarchical injector | props and context |
| Reactivity | Zone.js + change detection; now signals | re-render on state change |
| Async | RxJS observables everywhere | promises, `async/await` |
| Learning curve | steeper up front | gentler, then you choose everything |

**The one-line difference:** Angular gives you a complete, consistent kit and
expects you to work its way. React gives you rendering and expects you to bring
the rest.

Neither is better. They cost you in different places — Angular costs you up
front and saves you later; React costs you in a thousand small decisions
spread across the project's life.

---

## 2. Components and templates

```ts
@Component({
  selector: 'app-break-list',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <h2>{{ title }}</h2>
    @for (b of breaks(); track b.id) {
      <div class="row" [class.large]="b.deltaPaise > 30000">
        {{ b.externalTxnId }} — {{ b.deltaPaise | currency:'INR' }}
        <button (click)="claim(b)">Claim</button>
      </div>
    } @empty {
      <p>No breaks found.</p>
    }
  `,
  styles: [`.row { padding: 8px; }`],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BreakListComponent {
  title = 'Break queue';
  breaks = signal<BreakView[]>([]);

  claim(b: BreakView) { ... }
}
```

**Component styles are scoped by default** — Angular adds attribute selectors so
your CSS cannot leak. React needs CSS modules or a CSS-in-JS library to get the
same property.

---

## 3. Standalone components

The most important modern change. **NgModules are no longer needed**, and new
code should not use them.

```ts
// Before — every component had to be declared in a module
@NgModule({ declarations: [AppComponent], imports: [BrowserModule] })
export class AppModule {}

// Now — the component declares its own dependencies
@Component({ standalone: true, imports: [CommonModule, RouterLink], ... })
export class BreakListComponent {}

// Bootstrap without a module at all
bootstrapApplication(AppComponent, {
  providers: [
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideAnimations(),
  ],
});
```

> **Interview signal:** if you describe Angular in terms of NgModules,
> `declarations` and `SharedModule`, you sound like you stopped following it
> around Angular 12. Standalone became the default in v17, and `provideX()`
> functions replaced the module imports.

---

## 4. Data binding and the new control flow

### The four bindings

```html
{{ value }}                      <!-- interpolation: component → view -->
[disabled]="isLoading"           <!-- property binding: component → view -->
(click)="onClick($event)"        <!-- event binding: view → component -->
[(ngModel)]="email"              <!-- two-way: "banana in a box" -->
```

`[(ngModel)]` is sugar for `[ngModel]` + `(ngModelChange)`.

### The new control flow (v17+)

```html
@if (loading) {
  <app-spinner />
} @else if (error) {
  <app-error [message]="error" />
} @else {
  <app-content />
}

@for (item of items; track item.id) {
  <app-row [item]="item" />
} @empty {
  <p>Nothing here.</p>
}

@switch (status) {
  @case ('PARSED') { <span>Ready</span> }
  @default { <span>Working…</span> }
}
```

This replaced `*ngIf`, `*ngFor` and `*ngSwitch`. It is faster (no directive
instantiation), type-checked, and **`track` is now mandatory on `@for`** —
Angular forcing the equivalent of React's `key`, for exactly the same reason.

> **The old `trackBy` was optional and routinely omitted**, which meant Angular
> re-created DOM nodes on every list change. Making it mandatory is the
> framework fixing a pit of failure, and it is a good thing to be able to
> explain.

---

## 5. Inputs, outputs and component communication

```ts
// Modern signal-based (v17.2+)
export class StatCardComponent {
  label = input.required<string>();
  hint  = input<string>('');                 // with a default
  claimed = output<string>();

  raise(id: string) { this.claimed.emit(id); }
}

// Classic decorator form — still everywhere
export class StatCardComponent {
  @Input({ required: true }) label!: string;
  @Output() claimed = new EventEmitter<string>();
}
```

```html
<app-stat-card [label]="'Recoverable'" (claimed)="onClaim($event)" />
```

**Data down via inputs, events up via outputs.** Structurally identical to
React's props-down / callbacks-up.

For distant components, use a **service** (section 6) rather than passing
through five layers — Angular's answer to prop drilling.

---

## 6. Dependency injection

**Angular's DI is genuinely better than React's context**, and this is worth
saying in an interview — it is the closest thing to what you already know from
Spring.

```ts
@Injectable({ providedIn: 'root' })        // a singleton for the whole app
export class BreakService {
  private http = inject(HttpClient);       // modern: inject() over constructor params

  getBreaks(): Observable<BreakView[]> {
    return this.http.get<BreakView[]>('/api/breaks');
  }
}

@Component({ ... })
export class BreakListComponent {
  private service = inject(BreakService);
}
```

### The hierarchical injector

```
Root injector          providedIn: 'root'      — one instance app-wide
  └─ Route injector    providers on a route    — one per lazy-loaded route
      └─ Component     providers: [X]          — one per component instance
```

A component asking for a dependency walks **up** the tree until it finds a
provider. Providing a service on a component gives each instance its own copy.

> **The Spring parallel is exact**, and worth stating: `@Injectable` is
> `@Service`, `providedIn: 'root'` is a singleton bean, `inject()` is
> constructor injection, and the hierarchical injector is Spring's parent/child
> `ApplicationContext`. If you can explain Spring DI you already understand
> Angular DI.

### Injection tokens

```ts
export const API_URL = new InjectionToken<string>('API_URL');
providers: [{ provide: API_URL, useValue: '/api' }]
const url = inject(API_URL);
```

For values that have no class to inject — the equivalent of `@Value` in Spring.

---

## 7. Change detection — and Zone.js

**The classic Angular interview question.**

### How it has always worked

Angular patches every async API in the browser — `setTimeout`, `addEventListener`,
`XMLHttpRequest`, promises — using **Zone.js**. When any of them fires, Zone
tells Angular "something might have changed", and Angular **walks the entire
component tree** re-evaluating template expressions.

That is why Angular "just works" without `setState`: it re-checks everything
after every async event.

### The cost, and OnPush

Checking every binding in a large tree on every keystroke is expensive.

```ts
changeDetection: ChangeDetectionStrategy.OnPush
```

With `OnPush`, a component is only re-checked when:

1. one of its `@Input()` references **changes** (by reference, not by content),
2. an event originates from inside it,
3. an observable it subscribes to via `| async` emits, or
4. you call `markForCheck()` manually.

> **This is why immutability matters in Angular too.** Mutating an array in
> place and passing the same reference to an `OnPush` child updates nothing —
> the identical bug as in React, for the identical reason.

### The direction of travel: zoneless

Angular is moving **away** from Zone.js toward signals-based change detection,
where a component re-renders because a signal it read actually changed — no
global tree walk.

```ts
bootstrapApplication(App, { providers: [provideExperimentalZonelessChangeDetection()] });
```

> **Interview answer:** "Historically Angular used Zone.js to monkey-patch async
> APIs, so any async event triggered a check of the whole component tree.
> `OnPush` narrows that to reference changes on inputs. The current direction is
> zoneless, where signals track dependencies precisely and only the components
> that actually read changed state are updated — which is much closer to how
> React's model works, and removes a large, invisible runtime dependency."

---

## 8. Signals

Angular's reactive primitive (v16+), and the biggest change in years.

```ts
export class BreakListComponent {
  breaks = signal<BreakView[]>([]);
  filter = signal<string | null>(null);

  // derived — recomputes only when a dependency changes
  visible = computed(() =>
    this.filter() === null
      ? this.breaks()
      : this.breaks().filter(b => b.breakType === this.filter())
  );

  total = computed(() =>
    this.visible().reduce((sum, b) => sum + b.deltaPaise, 0)
  );

  constructor() {
    effect(() => console.log('filter is now', this.filter()));   // side effects
  }

  setFilter(t: string | null) { this.filter.set(t); }
  add(b: BreakView) { this.breaks.update(list => [...list, b]); }
}
```

```html
<p>{{ total() }}</p>          <!-- call it like a function -->
```

| | |
|---|---|
| `signal(v)` | writable state |
| `computed(fn)` | derived, cached, auto-tracked |
| `effect(fn)` | run a side effect when dependencies change |
| `.set(v)` / `.update(fn)` | write |

**The React parallel:** `signal` ≈ `useState`, `computed` ≈ `useMemo` (but with
automatic dependency tracking — no array), `effect` ≈ `useEffect` (again, no
dependency array).

> **Signals track dependencies automatically at runtime**, which removes the
> entire class of "wrong dependency array" bugs that React's hooks have. That is
> a genuinely good point to make when comparing the two.

---

## 9. RxJS, the parts you actually need

Angular uses observables for HTTP, routing and forms, so you cannot avoid RxJS.
You do not need all 100+ operators — you need about eight.

```ts
import { map, filter, switchMap, debounceTime, distinctUntilChanged,
         catchError, takeUntilDestroyed, shareReplay } from 'rxjs';

search = new FormControl('');

results$ = this.search.valueChanges.pipe(
  debounceTime(300),              // wait for typing to pause
  distinctUntilChanged(),         // ignore "same value again"
  switchMap(term => this.api.search(term)),   // ← cancels the previous request
  catchError(() => of([])),
  takeUntilDestroyed(),           // unsubscribe when the component is destroyed
);
```

### The four flattening operators — a favourite question

| Operator | On a new value | Use for |
|---|---|---|
| `switchMap` | **cancels** the previous inner observable | type-ahead search, navigation |
| `mergeMap` | runs all in parallel, any order | independent parallel work |
| `concatMap` | queues, preserves order | ordered writes |
| `exhaustMap` | **ignores** new values while one is running | preventing double-submit |

> `switchMap` for search, `exhaustMap` for a submit button, `concatMap` when
> order matters, `mergeMap` when nothing does. Being able to say that sentence
> is usually enough.

### Memory leaks

An unsubscribed observable keeps the component alive forever.

```ts
// ✅ modern — ties the subscription to the component's lifetime
source$.pipe(takeUntilDestroyed()).subscribe(...)

// ✅ best — let the template subscribe and unsubscribe for you
// template:  @if (data$ | async; as data) { ... }
```

**The `async` pipe is the correct default.** It subscribes, unsubscribes on
destroy, and marks `OnPush` components for check.

---

## 10. HttpClient and interceptors

```ts
@Injectable({ providedIn: 'root' })
export class BreakService {
  private http = inject(HttpClient);

  getBreaks(type?: string): Observable<BreakView[]> {
    let params = new HttpParams().set('limit', 50);
    if (type) params = params.set('type', type);
    return this.http.get<BreakView[]>('/api/breaks', { params });
  }
}
```

**Interceptors** are the equivalent of RTK Query's `prepareHeaders` plus its
401 wrapper — one place for cross-cutting concerns:

```ts
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(AuthStore).token();
  const authed = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(authed).pipe(
    catchError(err => {
      if (err.status === 401) inject(AuthStore).signOut();
      return throwError(() => err);
    })
  );
};

provideHttpClient(withInterceptors([authInterceptor]))
```

> Note `req.clone(...)` — **`HttpRequest` is immutable.** Same principle as
> everywhere else in this document.

---

## 11. Routing and guards

```ts
export const routes: Routes = [
  { path: '', component: DashboardComponent },
  { path: 'upload', component: UploadComponent, canActivate: [authGuard] },
  { path: 'claims/:id', component: ClaimDetailComponent },
  {
    path: 'admin',
    loadChildren: () => import('./admin/routes').then(m => m.ADMIN_ROUTES),  // lazy
  },
  { path: '**', component: NotFoundComponent },
];

export const authGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthStore);
  return auth.isAuthenticated() || inject(Router).createUrlTree(['/login']);
};
```

**Guards are a genuine Angular advantage** over React Router — `CanActivate`,
`CanDeactivate` (an "unsaved changes?" prompt) and resolvers are built in and
declarative, where React needs you to build them.

And the same deployment trap applies: a single-page app needs `try_files $uri
$uri/ /index.html` in nginx, or every deep link 404s.

---

## 12. Forms

### Reactive forms — use these

```ts
form = inject(FormBuilder).group({
  email: ['', [Validators.required, Validators.email]],
  password: ['', [Validators.required, Validators.minLength(12)]],
});

submit() {
  if (this.form.invalid) { this.form.markAllAsTouched(); return; }
  this.auth.login(this.form.getRawValue()).subscribe();
}
```

```html
<form [formGroup]="form" (ngSubmit)="submit()">
  <input formControlName="email" />
  @if (form.controls.email.touched && form.controls.email.invalid) {
    <span class="error">A valid email is required</span>
  }
  <button [disabled]="form.invalid">Sign in</button>
</form>
```

**Reactive over template-driven:** the model is defined in TypeScript, so it is
type-checked, unit-testable without rendering, and validation logic lives in
code rather than in attributes.

Custom validator:

```ts
const strongPassword: ValidatorFn = (c) =>
  /[A-Z]/.test(c.value) && /[0-9]/.test(c.value) ? null : { weak: true };
```

---

## 13. Lifecycle hooks

| Hook | Runs |
|---|---|
| `ngOnInit` | once, after the first `@Input` values are set |
| `ngOnChanges` | whenever an input changes (gets a `SimpleChanges`) |
| `ngAfterViewInit` | after the view and `@ViewChild` refs exist |
| `ngOnDestroy` | on teardown — **unsubscribe here** |

```ts
export class UploadComponent implements OnInit, OnDestroy {
  ngOnInit() { this.load(); }
  ngOnDestroy() { this.poller?.unsubscribe(); }
}
```

> **The constructor is for dependency injection only.** Inputs are not yet
> bound when it runs, which is why initialisation goes in `ngOnInit`. That is a
> standard interview question and a standard mistake.

---

## 14. Angular vs React: the answer

You will be asked this, because both are on your CV. Here is a version that is
honest and makes you look thoughtful rather than fashionable.

> **"You already knew Angular. Why did you build this in React?"**
>
> "Two reasons, one about the market and one about me.
>
> The market reason is simple: most Java full-stack roles in India list React,
> so I wanted the project to demonstrate it rather than repeat a skill I already
> had.
>
> The personal reason is that I learn a framework properly by being forced to
> make the decisions it doesn't make for me. Angular hands you routing, HTTP,
> forms and DI with one opinion each. React made me choose a state library, a
> data-fetching approach and a routing library, and understand why — I ended up
> separating server state into RTK Query and client state into Redux slices, and
> I could not have articulated that distinction before.
>
> Having used both, I would pick Angular for a large team on a long-lived
> enterprise application: the opinions are a feature, the DI is genuinely better
> than React's context, and guards and reactive forms would otherwise be
> hand-rolled. I would pick React for a smaller team, a product that needs to
> move quickly, or where hiring matters — the pool is larger.
>
> The interesting thing is how much they are converging. Angular signals are
> essentially `useState` and `useMemo` with automatic dependency tracking, and
> `@for ... track` is `key` by another name — both frameworks arrived at the
> same answers to the same problems."

That answer works because it names a real trade-off, shows you learned
something specific, and does not trash either framework.

---

## 15. The traps, collected

| # | Trap | The answer |
|---|---|---|
| 1 | Describing Angular via NgModules | Standalone is the default since v17 |
| 2 | Mutating an array with `OnPush` | Reference did not change; nothing updates |
| 3 | `*ngFor` without `trackBy` | DOM re-created; `@for` now requires `track` |
| 4 | Forgetting to unsubscribe | Memory leak; `takeUntilDestroyed` or `| async` |
| 5 | `mergeMap` for a search box | Out-of-order results; use `switchMap` |
| 6 | No `exhaustMap` on submit | Double-submits |
| 7 | Logic in the constructor | Inputs are not bound yet; use `ngOnInit` |
| 8 | Mutating an `HttpRequest` | It is immutable; use `req.clone()` |
| 9 | Template-driven forms for complex cases | Reactive forms are testable and typed |
| 10 | Function calls in templates | Re-evaluated on every check; use `computed` |
| 11 | Subscribing inside a subscribe | Nest with `switchMap` instead |
| 12 | `providedIn: 'root'` for per-instance state | Provide it on the component |
| 13 | Nothing in `ngOnDestroy` | Timers and sockets outlive the component |
| 14 | No `try_files` in nginx | Deep links 404 |
| 15 | Eagerly loading every route | Use `loadChildren` |

---

## What to do next

1. **Rehearse the §14 answer out loud.** It is the one Angular question you are
   guaranteed to get, and it is genuinely easy to fumble.
2. **Rebuild one ReconPilot screen in Angular** — the break list is perfect.
   Signals for state, `@for ... track`, an `HttpClient` service. An afternoon,
   and you can then speak about both from the same example.
3. **Learn the four flattening operators cold.** `switchMap`, `mergeMap`,
   `concatMap`, `exhaustMap` — which cancels, which queues, which ignores. It
   comes up constantly.

---

That completes the concepts series. Next come the interview-question documents:
**Java and Spring Boot first**, then the rest.
