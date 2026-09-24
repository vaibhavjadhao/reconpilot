# JavaScript, the language

**Why a separate document.** React interviews always contain JavaScript
questions, and they are usually the ones that go badly — closures, `this`, and
the event loop. They are asked because they cannot be memorised from a tutorial;
you either understand the model or you don't.

If you only read three sections, make them **closures**, **the event loop**, and
**`this`**.

---

## Table of contents

1. [Types and coercion](#1-types-and-coercion)
2. [var, let, const and hoisting](#2-var-let-const-and-hoisting)
3. [Closures](#3-closures)
4. [`this`](#4-this)
5. [Prototypes](#5-prototypes)
6. [The event loop](#6-the-event-loop)
7. [Promises](#7-promises)
8. [async / await](#8-async--await)
9. [Arrays and objects](#9-arrays-and-objects)
10. [Destructuring, spread and rest](#10-destructuring-spread-and-rest)
11. [Modules](#11-modules)
12. [Equality, copying and references](#12-equality-copying-and-references)
13. [The traps, collected](#13-the-traps-collected)

---

## 1. Types and coercion

### The types

**Primitives** (immutable, compared by value): `string`, `number`, `boolean`,
`null`, `undefined`, `symbol`, `bigint`.

**Everything else is an object** — including arrays and functions — and is
compared by reference.

```js
typeof "a"          // "string"
typeof 1            // "number"
typeof true         // "boolean"
typeof undefined    // "undefined"
typeof Symbol()     // "symbol"
typeof 1n           // "bigint"
typeof {}           // "object"
typeof []           // "object"   ← not "array"
typeof function(){} // "function"
typeof null         // "object"   ← a famous bug, kept for compatibility
```

```js
Array.isArray([])          // true  — the correct array check
Object.is(NaN, NaN)        // true  — unlike ===
```

### null vs undefined

- **`undefined`** — "this has no value *yet*". A declared-but-unassigned
  variable, a missing property, a missing argument, a function with no return.
- **`null`** — "this is *deliberately* empty". You set it.

### Truthiness

**Only eight values are falsy:**

```
false, 0, -0, 0n, "", null, undefined, NaN
```

Everything else is truthy — including `[]`, `{}`, `"0"`, `"false"` and
`function(){}`. That is the list to memorise.

### == vs ===

```js
1 == "1"            // true   — coerces
1 === "1"           // false  — no coercion ✅
null == undefined   // true   — the one useful coercion
null === undefined  // false
NaN === NaN         // false  — NaN is not equal to itself
[] == false         // true   😱
[] == ![]           // true   😱😱
```

**Always use `===`.** The single accepted exception is `x == null`, which
checks for `null` *or* `undefined` in one go.

### Modern null handling

```js
// ?? — nullish coalescing: only null/undefined fall through
const limit = input ?? 50          // 0 stays 0 ✅
const bad   = input || 50          // 0 becomes 50 ☠️

// ?. — optional chaining
const file = e.target.files?.[0]
const name = user?.profile?.name
obj.method?.()                      // call only if it exists
```

`??` versus `||` is a real bug source: `||` treats `0`, `""` and `false` as
missing. For a numeric limit or a boolean flag, that is wrong.

---

## 2. var, let, const and hoisting

| | Scope | Hoisted | Re-declarable | Reassignable |
|---|---|---|---|---|
| `var` | **function** | yes, as `undefined` | yes | yes |
| `let` | block | yes, but in the TDZ | no | yes |
| `const` | block | yes, but in the TDZ | no | **no** |

```js
console.log(a)   // undefined — declaration hoisted, assignment not
var a = 1

console.log(b)   // ReferenceError: Cannot access 'b' before initialization
let b = 1        // the Temporal Dead Zone
```

**Hoisting** means declarations are processed before any code runs. `var` is
initialised to `undefined`; `let` and `const` exist but are unreachable until
their declaration — the **TDZ**, which turns a silent `undefined` bug into a
loud error.

### The classic loop bug

```js
for (var i = 0; i < 3; i++) {
  setTimeout(() => console.log(i), 100)
}
// 3, 3, 3   — one shared `i`, function-scoped, already 3 by the time they run

for (let i = 0; i < 3; i++) {
  setTimeout(() => console.log(i), 100)
}
// 0, 1, 2   — `let` creates a NEW binding per iteration ✅
```

**This is asked constantly.** The explanation: `var` is function-scoped so all
three closures capture the same variable; `let` is block-scoped and the loop
creates a fresh binding each iteration, so each closure captures its own.

### `const` does not mean immutable

```js
const user = { name: "a" }
user.name = "b"        // ✅ allowed — the OBJECT is mutable
user = {}              // ❌ TypeError — the BINDING cannot be reassigned

Object.freeze(user)    // now shallow-immutable
```

**Use `const` by default, `let` when you must reassign, `var` never.**

---

## 3. Closures

### The definition

**A closure is a function that remembers the variables from where it was
defined, even after that outer function has returned.**

```js
function makeCounter() {
  let count = 0                     // lives on after makeCounter returns
  return function () {
    count += 1
    return count
  }
}

const next = makeCounter()
next()   // 1
next()   // 2

const other = makeCounter()
other()  // 1 — a separate, independent `count`
```

### Why it works

A JavaScript function keeps a reference to the **scope** it was created in, not
a copy of the values. As long as the inner function is reachable, that scope
cannot be garbage collected.

### The analogy

A closure is a **backpack**. When a function is created it packs the variables
it can see, and carries them wherever it goes. Two functions from the same
factory get two separate backpacks.

### Where you have already used one

```tsx
// Every React event handler is a closure over that render's state
function Upload() {
  const [dragging, setDragging] = useState(false)

  const send = useCallback(async (file: File) => {
    const res = await upload(file).unwrap()       // closes over `upload`
    dispatch(showToast({ ... }))                  // closes over `dispatch`
  }, [upload, dispatch])
}
```

This is also the source of React's **stale closure** bug: a handler captures the
state from the render that created it, so an effect with a missing dependency
keeps seeing an old value forever.

### Private state without classes

```js
function createAccount(initial) {
  let balance = initial                  // genuinely private
  return {
    deposit: (n) => balance += n,
    get: () => balance,
  }
}
const a = createAccount(100)
a.balance     // undefined — unreachable from outside
```

> **Interview question:** *"What is a closure and why would you use one?"* — "A
> function bundled with the lexical scope it was defined in, so it keeps access
> to those variables after the outer function returns. Practically: private
> state without a class, function factories, partial application, and every
> callback in React — which is also why stale closures happen when a dependency
> array is wrong."

---

## 4. `this`

The most confusing thing in JavaScript, because **`this` depends on how a
function is called, not where it is defined** — except for arrow functions.

### The five rules, in priority order

```js
// 1. `new` — `this` is the newly created object
function Person(name) { this.name = name }
const p = new Person("V")             // this === p

// 2. Explicit binding — call / apply / bind
fn.call(obj, a, b)                    // this === obj
fn.apply(obj, [a, b])
const bound = fn.bind(obj)            // permanently bound

// 3. Method call — this is the object before the dot
obj.method()                          // this === obj

// 4. Plain call — undefined in strict mode, globalThis otherwise
fn()

// 5. Arrow function — NO own `this`; inherits from where it was DEFINED
const arrow = () => this
```

### The bug everyone hits

```js
const counter = {
  count: 0,
  incrementLater() {
    setTimeout(function () {
      this.count++        // ☠️ `this` is NOT counter — a plain call
    }, 100)
  },
}
```

Three fixes:

```js
// 1. Arrow function — inherits `this` lexically ✅
setTimeout(() => { this.count++ }, 100)

// 2. bind
setTimeout(function () { this.count++ }.bind(this), 100)

// 3. capture it (the old way)
const self = this
setTimeout(function () { self.count++ }, 100)
```

### Losing `this` by extracting a method

```js
const obj = { name: "x", greet() { return this.name } }
const fn = obj.greet
fn()              // undefined — the call site has no object before the dot

const bound = obj.greet.bind(obj)
bound()           // "x" ✅
```

This is exactly why React class components needed
`this.handleClick = this.handleClick.bind(this)` in the constructor — and one of
several reasons hooks replaced them.

> **Arrow functions have no `this`, no `arguments`, and cannot be used with
> `new`.** In a class field, an arrow captures the instance, which is why
> `handleClick = () => {...}` works without binding.

---

## 5. Prototypes

JavaScript has **prototypal** inheritance, not classical. Every object has a
hidden link to another object — its prototype — and property lookup walks that
chain.

```js
const animal = { speak() { return "..." } }
const dog = Object.create(animal)
dog.speak()                  // found on the prototype
Object.getPrototypeOf(dog)   // animal
```

```
dog → animal → Object.prototype → null
```

### class is syntax over prototypes

```js
class Animal {
  constructor(name) { this.name = name }
  speak() { return `${this.name} makes a sound` }
}

class Dog extends Animal {
  speak() { return `${super.speak()}: woof` }
}
```

`class` is **syntactic sugar**. Methods live on `Dog.prototype`, and `extends`
sets up the prototype chain. There is no separate class system underneath.

> **Interview question:** *"How does inheritance work in JavaScript?"* —
> "Through the prototype chain. Every object has an internal link to a
> prototype object, and a property lookup that misses walks up that chain until
> it finds the property or reaches null. `class` since ES6 is sugar over the
> same mechanism — methods are installed on the prototype, `extends` links one
> prototype to another. The practical consequence is that methods are shared
> across instances rather than copied, and that you can modify behaviour at
> runtime by changing a prototype."

---

## 6. The event loop

**The single most valuable JavaScript concept to understand properly.**

### The setup

JavaScript is **single-threaded**: one call stack, one thing at a time. Yet it
handles network requests, timers and clicks without blocking. The event loop is
how.

```
   ┌─────────────┐
   │  Call Stack │  ← the one thread. Runs to completion.
   └──────┬──────┘
          │ empty?
          ▼
   ┌──────────────────┐
   │  Microtask queue │  ← Promises, queueMicrotask, MutationObserver
   └──────┬───────────┘     DRAINED COMPLETELY first
          ▼
   ┌──────────────────┐
   │  Macrotask queue │  ← setTimeout, setInterval, I/O, UI events
   └──────────────────┘     ONE per loop iteration
```

### The algorithm

1. Run the current script to completion.
2. **Drain the entire microtask queue** — including microtasks added while
   draining.
3. Take **one** macrotask.
4. Render, if it's time.
5. Repeat.

### The interview question

```js
console.log('1')

setTimeout(() => console.log('2'), 0)

Promise.resolve().then(() => console.log('3'))

console.log('4')
```

**Output: `1, 4, 3, 2`**

Why:
- `1` and `4` are synchronous — same stack, in order.
- `3` is a **microtask** — runs as soon as the stack empties.
- `2` is a **macrotask** — waits for the next loop iteration, even at `0 ms`.

**Microtasks always beat macrotasks**, regardless of timer delay.

### Starvation

```js
function loop() { Promise.resolve().then(loop) }
loop()      // the microtask queue never empties — the page freezes ☠️
```

Because microtasks are drained *completely*, an endlessly self-scheduling
microtask blocks rendering and every timer forever. The same with
`setTimeout(fn, 0)` would not, because that yields between iterations.

### Blocking is real

```js
const start = Date.now()
while (Date.now() - start < 5000) {}    // the entire page is frozen for 5s
```

One thread. A long synchronous loop blocks clicks, animation and everything
else. Heavy work belongs in a **Web Worker** (browser) or a **worker thread**
(Node).

---

## 7. Promises

A promise is an object representing a value that will exist later. Three states:
**pending → fulfilled** or **pending → rejected**. Once settled, it never
changes.

```js
const p = new Promise((resolve, reject) => {
  setTimeout(() => resolve("done"), 100)
})

p.then(v => console.log(v))
 .catch(e => console.error(e))
 .finally(() => console.log("always"))
```

### Chaining

```js
fetch(url)
  .then(r => r.json())            // returning a value → next .then receives it
  .then(d => transform(d))
  .then(d => save(d))             // returning a PROMISE → the chain waits for it
  .catch(e => handle(e))          // catches any rejection above it
```

**Each `.then` returns a new promise**, which is what makes flat chaining work
and escapes callback hell.

### The combinators

```js
await Promise.all([a, b, c])          // all succeed, or reject on the FIRST failure
await Promise.allSettled([a, b, c])   // waits for all; never rejects
await Promise.race([a, timeout])      // first to SETTLE, success or failure
await Promise.any([a, b, c])          // first to SUCCEED
```

`Promise.race` with a timeout is the standard way to bound a slow call:

```js
const withTimeout = (p, ms) =>
  Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), ms))])
```

### The mistake

```js
// ❌ fetch is NOT awaited; the function returns before it finishes
items.forEach(async (item) => { await save(item) })

// ✅ sequential
for (const item of items) { await save(item) }

// ✅ parallel
await Promise.all(items.map(item => save(item)))
```

**`forEach` ignores the promise a callback returns.** This silently produces
"the work didn't happen yet" bugs.

---

## 8. async / await

Syntax over promises. `async` makes a function return a promise; `await` pauses
until a promise settles.

```js
async function load() {
  try {
    const r = await fetch(url)
    if (!r.ok) throw new Error(`HTTP ${r.status}`)   // fetch does NOT throw on 4xx/5xx!
    return await r.json()
  } catch (e) {
    console.error(e)
    throw e
  } finally {
    setLoading(false)
  }
}
```

> **`fetch` only rejects on a network failure.** A 404 or a 500 is a
> *successful* fetch with `ok === false`. Forgetting this means server errors
> sail through as success — a genuinely common bug.

### Sequential vs parallel

```js
// ❌ 3 seconds — each waits for the last, for no reason
const a = await fetchA()
const b = await fetchB()
const c = await fetchC()

// ✅ 1 second — all three start immediately
const [a, b, c] = await Promise.all([fetchA(), fetchB(), fetchC()])
```

**Only `await` sequentially when a later call genuinely needs an earlier
result.** This is the most common performance mistake in async code.

### Top-level await

Allowed in ES modules. Not in CommonJS.

---

## 9. Arrays and objects

```js
// Transform — all return a NEW array
arr.map(x => x * 2)
arr.filter(x => x > 0)
arr.reduce((acc, x) => acc + x, 0)
arr.flat(2)
arr.flatMap(x => [x, x])

// Search
arr.find(x => x.id === id)          // the element, or undefined
arr.findIndex(x => x.id === id)     // the index, or -1
arr.includes(v)
arr.some(x => x > 0)                // any?
arr.every(x => x > 0)               // all?

// Order — ⚠️ these MUTATE
arr.sort((a, b) => a - b)           // default sort is LEXICOGRAPHIC!
arr.reverse()
[...arr].sort()                     // copy first ✅
arr.toSorted()                      // ES2023 — returns a new array
```

### The sort trap

```js
[10, 9, 1].sort()                // [1, 10, 9]  — compared as STRINGS 😱
[10, 9, 1].sort((a, b) => a - b) // [1, 9, 10] ✅
```

### Objects

```js
Object.keys(o)        // own enumerable keys
Object.values(o)
Object.entries(o)     // [[k, v], ...]
Object.fromEntries(pairs)
Object.assign({}, a, b)
Object.freeze(o)      // shallow

"name" in o           // includes the prototype chain
Object.hasOwn(o, "name")   // own properties only ✅ (modern)
```

---

## 10. Destructuring, spread and rest

```js
// Objects — with renaming and defaults
const { batchId, status, rowCount = 0 } = batch
const { data: breaks = [] } = useGetBreaksQuery()

// Arrays
const [first, second, ...others] = items
const [, second] = items                    // skip one

// Nested
const { user: { profile: { name } } } = response

// Parameters — extremely common in React
function StatCard({ label, value, hint }) { }

// Spread — SHALLOW copies
const copy   = { ...original }
const merged = { ...a, ...b }               // b wins on conflicts
const added  = [...items, newItem]

// Rest
function sum(...numbers) { return numbers.reduce((a, b) => a + b, 0) }
const { id, ...rest } = obj                 // everything except id
```

### Spread is shallow

```js
const a = { nested: { x: 1 } }
const b = { ...a }
b.nested.x = 2
a.nested.x        // 2 — the SAME nested object 😱

const deep = structuredClone(a)             // a real deep copy
```

This bites in React: spreading state that contains an object and then mutating
the inner object mutates the original too.

---

## 11. Modules

```js
// ESM — the standard
export const x = 1
export default function App() {}
export { a, b }

import App, { a, b } from './mod.js'
import * as everything from './mod.js'
const mod = await import('./heavy.js')      // dynamic, returns a promise
```

```js
// CommonJS — Node's legacy system
const x = require('./mod')
module.exports = { x }
```

| | ESM | CommonJS |
|---|---|---|
| Loading | static, hoisted | dynamic, at runtime |
| Tree-shaking | ✅ | ❌ |
| Top-level await | ✅ | ❌ |
| In Node | `.mjs` or `"type": "module"` | default |

**Static imports are what make tree-shaking possible** — the bundler can see at
build time which exports are used and drop the rest. `require()` inside an `if`
cannot be analysed that way.

Dynamic `import()` is how React code-splitting works:

```tsx
const ClaimDetail = lazy(() => import('./pages/ClaimDetail'))
```

---

## 12. Equality, copying and references

```js
// Primitives — by value
let a = 1, b = a
b = 2
a          // 1

// Objects — by reference
let x = { n: 1 }, y = x
y.n = 2
x.n        // 2 — same object

{} === {}          // false — different objects
[1] === [1]        // false
```

### Copying, three levels

```js
const shallow = { ...obj }                  // one level deep
const shallow2 = Object.assign({}, obj)

const deepOld = JSON.parse(JSON.stringify(obj))
//   ⚠️ loses Date, Map, Set, undefined, functions; breaks on cycles

const deep = structuredClone(obj)           // ✅ modern, handles cycles and Dates
```

---

## 13. The traps, collected

| # | Trap | The answer |
|---|---|---|
| 1 | `==` coercion | Always `===`, except `x == null` |
| 2 | `typeof null === "object"` | Historical bug; use `x === null` |
| 3 | `typeof [] === "object"` | Use `Array.isArray` |
| 4 | `[10,9,1].sort()` | Lexicographic; pass a comparator |
| 5 | `sort`/`reverse` mutate | Copy first, or use `toSorted` |
| 6 | `var` in a loop with a closure | Use `let` — a new binding per iteration |
| 7 | `this` in a plain callback | Use an arrow function |
| 8 | Extracting a method loses `this` | `bind`, or an arrow class field |
| 9 | `||` for a default | `0` and `""` are falsy; use `??` |
| 10 | Spread is shallow | `structuredClone` for deep |
| 11 | `JSON.parse(JSON.stringify())` | Loses Dates, Maps, undefined; breaks on cycles |
| 12 | `forEach` with `async` | Ignores the promise; use `for...of` or `Promise.all` |
| 13 | Sequential `await` for independent calls | `Promise.all` |
| 14 | `fetch` not throwing on 404/500 | Check `response.ok` |
| 15 | `Promise.all` rejecting on one failure | `allSettled` when partial results are fine |
| 16 | `setTimeout(fn, 0)` running immediately | It is a macrotask; microtasks go first |
| 17 | Self-scheduling microtask | Starves rendering and every timer |
| 18 | `const` means immutable | It means the binding cannot be reassigned |
| 19 | `NaN === NaN` is false | `Number.isNaN` or `Object.is` |
| 20 | `in` includes the prototype chain | `Object.hasOwn` |

---

## What to do next

1. **Predict, then run.** Write the `1 / setTimeout / Promise / 4` snippet in
   the browser console, say the order out loud before pressing enter. If you
   get it right and can explain *why*, you have the event loop.
2. **Write `makeCounter` from memory.** Then make two counters and prove they
   are independent. That is closures done.
3. **Break `this` on purpose.** Put `this.count++` in a `setTimeout` callback,
   watch it fail, then fix it three different ways.

Next: `10-testing.md`.
