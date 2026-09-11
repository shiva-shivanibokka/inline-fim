# Inline FIM

Inline code completion for JetBrains IDEs, powered by a fill-in-the-middle model
running locally. Grey text appears ahead of your cursor; Tab accepts it, Esc
dismisses it. No API key, no network, nothing leaves the machine.

```
def binary_search(arr, target):
    lo, hi = 0, len(arr) - 1
    while lo <= hi:
        mid = (lo + hi) // 2
        ⎸if arr[mid] == target:          <- grey, press Tab
             return mid
    return -1
```

Built against IntelliJ IDEA Community 2025.2.6.2 from the
[JetBrains plugin template](https://github.com/JetBrains/intellij-platform-plugin-template).

---

## Four things worth a reviewer's time

Each is documented in full below, with the run that produced it.

- **A latency bug that manual testing could not see.** Typing in the sandbox,
  completions felt "slightly slow". Measured, they were 2194ms against a 300ms
  budget, and the cause was not the model: `localhost` resolves to IPv6 first on
  Windows, Ollama binds IPv4 only, and every connection paid ~2s failing over.
  One word, 36x — [details](#the-two-seconds-that-were-not-the-models-fault).

- **A quality bug that reading the code could not see.** The offline eval caught
  the model emitting a raw `<|cursor|>` token into completions
  (`want: 'er {'`, `got: 'er() {<|cursor|>'`). It had been in every build until
  something compared output against ground truth —
  [details](#completion-quality).

- **A test that was rewritten because it could not fail.** The first
  cancellation test waited 250ms against a 1000ms stream, so an implementation
  that ignored cancellation entirely would also have passed. That is decoration,
  not a test. It now waits past the full stream duration, and was verified by
  mutation: delete the one line that closes the socket, watch it go red —
  [details](#tests).

- **Telemetry that found two bugs the benchmarks could not.** Session one: 83%
  of suggestions were being cut off at the line cap, which put real p50 total at
  1567ms where the benchmark said 210ms, and left broken Python that disabled the
  IDE's own indent-on-Enter. Session two: Ollama's log showed request durations
  climbing 2.4s to 7.2s across one burst, because cancellation was waiting on a
  socket read that a queue had made slow. Neither is visible from the code, and
  neither showed up in a benchmark --
  [details](#telemetry-and-what-it-found).

All four came from measuring something rather than reasoning about it, and the
fourth is the sharpest of them: the benchmark that missed the latency regression
was honest, repeatable, and measuring the wrong situation. Synthetic caret
positions let the model stop early; real editing positions did not. Only
telemetry from someone actually typing caught it.

---

## Running it

### 1. Prerequisites

**JDK 21.** Anything newer will not work -- the IntelliJ Platform Gradle plugin
targets 21. If `java -version` disagrees with what Gradle uses, set `JAVA_HOME`
explicitly.

**[Ollama](https://ollama.com)**, installed *and running*. On macOS and Windows
the desktop app starts a background server; on Linux, `ollama serve`.

### 2. Get the model

```bash
ollama pull qwen2.5-coder:1.5b-base     # ~1 GB
```

The `-base` suffix is not optional. `-instruct` variants are chat-tuned and will
write prose into your buffer; only the base models were trained with the
fill-in-the-middle objective. See [below](#why-fill-in-the-middle-not-chat).

### 3. Check it before launching the IDE

**Do this.** This plugin stays silent when it is unsure -- that is the point of
it -- so a broken setup and a cautious plugin look identical from the editor. Two
seconds here saves you wondering whether it works:

```bash
ollama list                             # qwen2.5-coder:1.5b-base should appear
curl http://127.0.0.1:11434/api/tags    # should return JSON, not "connection refused"
```

If you want to confirm fill-in-the-middle itself works before involving an IDE at
all:

```bash
curl -s http://127.0.0.1:11434/api/generate -d '{
  "model":"qwen2.5-coder:1.5b-base",
  "prompt":"<|fim_prefix|>def add(a, b):
    return <|fim_suffix|>
<|fim_middle|>",
  "raw":true, "stream":false, "options":{"num_predict":16}
}'
```

The `response` field should begin `a + b`. If it comes back as prose, or with
markdown fences, you are on an `-instruct` tag.

### 4. Run it

```bash
git clone https://github.com/shiva-shivanibokka/inline-fim
cd inline-fim

./gradlew runIde        # sandbox IDE with the plugin loaded
./gradlew test          # 27 tests, no Ollama needed, no network
./gradlew buildPlugin   # -> build/distributions/inline-fim-0.1.0.zip
```

On Windows use `gradlew.bat`, or run the `Run Plugin` configuration from the IDE.

`runIde` takes a few minutes the first time -- it downloads IntelliJ IDEA
Community 2025.2.6.2 and the Python plugin. Subsequent runs are seconds.

### 5. See it work

In the sandbox IDE that opens:

1. Open or create a folder, and a **`.py` file** in it.
2. Type something ordinary and pause:
   ```python
   def running_total(nums):
       total = 0
       for n in nums:
   ```
3. Grey text appears ahead of the caret. **Tab** accepts it, **Esc** dismisses it.

The **first** suggestion of a session may take ~4.5s if Ollama has not loaded the
model yet; after that it is tens of milliseconds. A warm-up request fires when a
project opens to hide most of that.

### If no grey text appears

In likelihood order:

- **Ollama is not running.** Step 3. This is the overwhelmingly common cause, and
  it is invisible from the editor by design.
- **You are somewhere the plugin deliberately stays quiet** -- mid-word, inside a
  string or comment, or on a line that is already complete. Try pressing Enter
  for a fresh blank line inside a function body. The full list is
  [here](#when-it-deliberately-says-nothing).
- **You did not pause.** There is a 200ms debounce; typing continuously cancels
  each request before it can be shown.
- **Still nothing:** `Help → Show Log in Explorer/Finder` in the sandbox IDE and
  grep for `inline-fim`. Every request logs its timings, every silence logs its
  reason, and a failed warm-up logs why.

Settings live at **Settings → Tools → Inline FIM** -- model, token and line caps,
debounce, and every silence rule as an individual toggle.

### Platform: this was built and measured only on Windows

Everything here -- every number, every run of every script, every session in the
sandbox -- is Windows 11 on one laptop. Nothing in the plugin is Windows-specific
and the Gradle build is the JetBrains template's, so macOS and Linux should be
unremarkable, but *should* is the operative word: **it has not been run on
either**, and this README will not imply otherwise.

Three things a reader on another platform should know:

- **`./gradlew` vs `gradlew.bat`.** The commands above use the POSIX wrapper.
  On Windows `cmd`/PowerShell, use `gradlew.bat`, or just run the `Run Plugin`
  configuration from inside the IDE.
- **`buildPlugin` needs the sandbox IDE closed, on Windows.** `prepareSandbox`
  rewrites the sandbox plugin directory, and a running IDE holds memory-mapped
  handles on the jars inside it, which fails as *"cannot be performed on a file
  with a user-mapped section open"*. It is a file lock, not a build problem.
  Unix lets you replace an open file, so this should not arise there -- untested.
- **The `127.0.0.1` decision was forced by Windows**, where `localhost` resolves
  to `::1` first and Ollama binds IPv4 only, costing ~2s per connection. The
  [story is below](#the-two-seconds-that-were-not-the-models-fault). Using the
  literal address is harmless everywhere, so the fix is not platform-specific
  even though the bug was -- but a Linux reader should know that number was not
  measured on their machine.

The hardware matters too, and more than the OS does. Every latency figure in
this README is an RTX 4060 Laptop with the model resident in VRAM. On a
CPU-only machine prefill would dominate, and the context-window reasoning in
[context assembly](#context-assembly) would deserve revisiting rather than
being carried over.

---

## Why fill-in-the-middle, not chat

Autocomplete is a fill-in-the-middle problem. An editor hands you two things: the
text before the caret and the text after it. FIM models are trained on exactly
that shape, using special tokens in their vocabulary:

```
<|fim_prefix|>def binary_search(arr, target):
    lo, hi = 0, len(arr) - 1
    while lo <= hi:
        mid = (lo + hi) // 2
        <|fim_suffix|>
    return -1
<|fim_middle|>
```

The model returns the middle and stops. What it does **not** do is wrap the answer
in a markdown fence, preface it with "Here's the completion:", or restate the
`return -1` that it can see already exists below the hole.

A chat model asked to "complete this code" does all three, and you then spend
effort stripping its politeness back off — slower, and lossy. Two details make
the difference:

- **`"raw": true` on the Ollama request.** Without it, Ollama wraps the prompt in
  the model's chat template and the FIM tokens degrade into ordinary text.
- **A `-base` tag, not `-instruct`.** The instruct variants are chat-tuned and
  will write prose into your buffer. The base models are raw next-token
  predictors trained with the FIM objective.

---

## Architecture

```
IntelliJ InlineCompletionProvider          (the platform calls us)
  └─ debounce 200ms                        DebouncedInlineCompletionProvider
  └─ silence rules                         text + PSI, before any request
  └─ context assembly                      3000-char prefix / 1000-char suffix
  └─ LRU cache                             identical context never re-queries
  └─ Ollama /api/generate, raw + stream    cancellable HTTP
  └─ trim to line budget
  └─ telemetry                             one JSON record per suggestion
```

Six source files, 939 lines including the commentary, plus 435 lines of tests
and 671 lines of measurement harness.

**No third-party runtime dependencies.** `HttpClient` is JDK 21; Gson and
`kotlinx-coroutines-future` already ship inside the IntelliJ platform (both in
`util-8.jar` — checked before writing any HTTP or JSON code). JUnit is the only
added artifact and it is test-only.

### Which extension point, and why

`com.intellij.inline.completion.provider`, implementing
`InlineCompletionProvider` (2024.1+). Verified against the 2025.2 platform source
rather than from memory, which mattered: the API has moved.

`InlineCompletionSuggestion` now lives in
`com.intellij.codeInsight.inline.completion.suggestion`. The older top-level class
of the same name is `@Deprecated` and `@ScheduledForRemoval`. The two differ by
one package segment, so tutorials written against 2024.1 compile with a warning
that is easy to miss. This uses the current one.

Worth stating: `InlineCompletionProvider` sits in the platform's
`api-dump-unreviewed.txt`, not `api-dump.txt` — public and usable, but outside
JetBrains' binary-compatibility guarantee. It can shift between releases.

The template ships pointing at `intellijIdea(...)`, which resolves to **Ultimate**
and runs unlicensed in the sandbox with its paid plugins disabled. Switched to
`intellijIdeaCommunity(...)` so this is verified against the edition most people
actually run.

### Context assembly

**3000 characters of prefix, 1000 of suffix, both snapped to line boundaries.**

Whole-file context spends the latency budget on prefill for tokens the model does
not need. Current-line context strips the function signature and local variables
the completion has to agree with. So: a window.

The 3:1 asymmetry is deliberate — code depends far more on what precedes the
caret than what follows. The suffix's main job is telling the model *where to
stop*, which is why the example above doesn't re-emit `return -1`.

Line snapping matters because handing a model half an identifier teaches it
nothing except that the file is corrupt.

Measured cost: prefill for 855 tokens is **91ms**, so this window is nearly free
on a GPU. On a CPU-only machine prefill would dominate and this decision would
deserve revisiting.

---

## Measured latency

Hardware: RTX 4060 Laptop, i7-13700HX, Windows 11, Ollama 0.33.1, model resident.
Harness: `bench/latency.py`, replaying real caret positions from real source files
through the same context window and FIM format the plugin uses.

| | p50 | p95 | max |
|---|---|---|---|
| **time to first token** | **61ms** | **133ms** | 145ms |
| total | 210ms | 861ms | 869ms |

Run-to-run variance is real: a later run of the same configuration gave a p50 of
85ms. Read these as tens of milliseconds, not precise figures.

**Total is the number that matters, and this table understates it.** Nothing is
rendered until the whole completion has arrived, so time-to-first-token is an
internal measure -- the user waits for total. Worse, the 210ms above is a
benchmark artefact: synthetic caret positions let the model stop generating
early, and real editing positions did not. Telemetry from an actual session put
p50 total at 1567ms.

That is fixed. The stream is now cancelled as soon as enough lines have arrived
to fill the display cap, since nothing after that can change what is shown:

| | p50 | p95 |
|---|---|---|
| read every token | 927ms | 1636ms |
| **stop at the line cap** | **189ms** | **273ms** |

Measured over 14 caret positions in a realistic Python file. 4.9x at p50, and
p95 total lands inside the 300ms budget rather than five times outside it. The
story is in [telemetry](#telemetry-and-what-it-found).

### The two seconds that were not the model's fault

The first honest measurement was **p50 TTFT of 2194ms** against a ~300ms budget.
Nothing felt slow, the GPU was engaged, and the model was fine. Ollama's own
timing fields showed why it wasn't the model:

```
 chars  ptok     wall     load  prefill      gen  ntok
  4000   855   2499.3      7.2     91.3    316.0    32
```

Load 7ms, prefill 91ms, generation 316ms — about 415ms of a 2499ms wall clock.
The missing two seconds were constant regardless of context size, which is not
what prefill cost looks like.

The request URL said `localhost`. On Windows that resolves to `::1` first, Ollama
binds IPv4 only, and every new connection stalls failing over. Measured side by
side, identical request:

| host | wall | Ollama's own total | gap |
|---|---|---|---|
| `localhost` | 2428ms | 362ms | **2065ms** |
| `127.0.0.1` | 101ms | 100ms | 1ms |

One word. **TTFT p50 2194ms → 61ms.**

I would never have found this by reading the code. Every component was healthy
and the bug was in a string literal. It is the strongest argument in this project
for building the measuring harness before trusting the feeling that something is
fast.

---

## Choosing the model

Same eval, same machine, three sizes:

| model | exact match | prefix match | TTFT p50 | TTFT p95 | total p50 |
|---|---|---|---|---|---|
| 0.5B base | 31.2% | 32.5% | 63ms | 103ms | 292ms |
| **1.5B base** | **41.2%** | **46.2%** | **85ms** | **148ms** | **461ms** |
| 3B base | 43.8% | 50.0% | 119ms | 240ms | 772ms |

**1.5B is the knee.** Going up from 0.5B buys 10 points of exact match for 22ms of
TTFT. Going up to 3B buys 2.6 more points for another 34ms of TTFT and 311ms of
total time — and 3B returned nothing at all on 6% of cases, against 0% for 1.5B.

One artefact worth naming, because it nearly misled me: measured by *total* time
at a fixed 64-token cap, 0.5B looked **slower** than 1.5B (221ms vs 144ms). That
is not model speed — the smaller model is worse at knowing when to stop, so it
generates more tokens before hitting one. Total time conflates speed with
verbosity. TTFT separates them, and does so monotonically with size.

### Why not a bigger model

The obvious objection to a 1.5B model is that 70B models exist and are better at
code. They are. They are also unusable here, for a reason worth spelling out.

Generating a token requires reading every active weight out of memory, once. So
generation speed is bounded by **memory bandwidth**, not by compute:

```
tokens/sec  ~=  effective bandwidth (GB/s)  /  model size (GB)
```

If that is really the binding constraint, then `tok/s x size` should come out
roughly constant across model sizes on the same machine. `bench/throughput.py`
checks that. Measured on an RTX 4060 Laptop (8 GB VRAM), 12 caret positions per
model, token counts and durations taken from Ollama's own `eval_count` /
`eval_duration` rather than from wall-clock and a chars-per-token guess:

| model | weights | generation | tok/s x GB |
|---|---|---|---|
| 0.5B base | 0.53 GB | 182 tok/s | 96 GB/s |
| 1.5B base | 0.99 GB | 93 tok/s | 92 GB/s |
| 3B base | 1.90 GB | 63 tok/s | 119 GB/s |

The product holds across a 3.6x range of model sizes. Generation here is
bandwidth-bound at roughly **100 GB/s effective**, and model size buys latency
at a fixed, predictable exchange rate.

Four runs of that script gave 107, 103, 95 and 102 GB/s -- about 10% spread,
from thermal throttling on a laptop GPU and contention with whatever else is
running. The conclusion below is two orders of magnitude away from the budget,
so it does not depend on that precision.

Which lets the 70B case be arithmetic on a measured constant rather than a
guess. A 70B model at 4-bit is about **40 GB of weights**. That does not fit in
8 GB of VRAM, so it would spill to system memory and run on the CPU, slower
still. But even granting it the full GPU bandwidth measured above:

```
100 GB/s / 40 GB  ~=  2.5 tokens/sec
```

A typical completion here is around 30 tokens. That is **12 seconds**, against a
300ms budget, and it is the optimistic figure. The line would be finished, and
so would the next one.

That last step is arithmetic, not a measurement, and the README should say so
plainly: no 70B model was run here, because none fits. What was measured is the
constant it rests on.

This is not a claim that 70B models cannot do autocomplete. It is a claim about
where they have to run. An H100 has ~3350 GB/s and 80 GB of VRAM, so 40 GB of
weights fit and the same arithmetic gives ~84 tok/s -- comfortable. That is what
hosted completion products are doing. It also means a network round trip, an API
key, and the file leaving the machine, which are the three things this project
was built to avoid.

### The more interesting reason

Bandwidth is the hard limit, but it is not the only argument, and on its own it
would just be a story about hardware.

Look again at the quality curve above. 0.5B to 1.5B buys 10 points of exact
match. 1.5B to 3B buys 2.6. The returns are already flattening hard, at 3B, on a
curve that has 70B somewhere far off the right-hand edge.

That is not an accident of this eval. Autocomplete is mostly a local, syntactic
task: close the bracket, finish the call, match the naming convention from two
lines up, respect the indentation. Almost everything needed to do it well is in
the few thousand characters already in the prompt. What a much larger model
brings is world knowledge and multi-step reasoning, and this task leans on
neither. Scale is being spent on capability the problem does not use.

So the honest summary is that the binding constraint is latency, the quality gap
to a much larger model is narrower here than it would be on a reasoning task,
and the gap that does remain is better closed with task-specific training data
than with parameters. Which is the argument for collecting the accept/reject
signal in the first place -- see [telemetry](#telemetry-and-what-it-found).

---

## Completion quality

`bench/offline_eval.py` holds out part of a real line, asks the model to
reconstruct it, and compares against what the author actually wrote. Prefix ends
at the caret and suffix starts at the *next* line, so the model cannot see the
answer.

80 cases over this repo's own Kotlin:

| | |
|---|---|
| exact match | 40.0% |
| prefix match | 45.0% |
| proposed something | 100% |

**This is not an accept rate**, and the distinction matters. Ground truth is one
specific author's exact text — a harsh judge that scores a perfectly good
completion at zero for choosing a different variable name. Treat it as a floor on
quality.

It earned its keep immediately by catching a bug no amount of manual testing had
surfaced:

```
want: 'er {'
got : 'er() {<|cursor|>'
```

`<|cursor|>` is in Qwen2.5-Coder's vocabulary, wasn't in the stop list, and would
have rendered as literal garbage in the editor. Fixing it moved the score from
41.2% to 40.0% — **one case in eighty, which is noise.** The value of that fix is
correctness, not the metric.

---

### The same eval on Python

The plugin is used on Python; the numbers above are its own Kotlin. That is a
mismatch worth closing, so the same harness was pointed at CPython's standard
library -- `json`, `email`, `http`, `csv`, `dataclasses`:

| corpus | n | exact | prefix | nonempty |
|---|---|---|---|---|
| this project's Kotlin | 80 | 40.0% | 45.0% | -- |
| CPython stdlib | 120 | **54.2%** | 58.3% | 100% |

**Do not read that gap as "better at Python".** CPython's standard library is
almost certainly in Qwen2.5-Coder's training data, and this project's Kotlin was
written days ago and certainly is not. Some part of 54.2% is recall rather than
inference, and this eval cannot separate the two.

That makes the pair more useful than either number alone. 40% is a floor on
genuinely unseen code; 54% is what it looks like on code the model may have
memorised. The honest claim is that real performance sits between them, nearer
the bottom for a private codebase and nearer the top for ordinary library-heavy
code. Measuring it properly needs a corpus with a known cutoff date -- repositories
published after the model's training cut, which is the standard fix for
contamination and is not something a weekend project should claim to have done.

---

## When it deliberately says nothing

A tool that suggests constantly gets switched off. Silence is a feature.

| rule | stays quiet on |
|---|---|
| mid-word | `fiz⎸zbuzz` |
| code after the caret | `total = ⎸ + tail` |
| line already finished | `int x = compute();⎸` |
| inside a comment | `# TODO: ⎸` |
| inside a string | `print("hello ⎸")` |
| echoes what follows | suggesting `return -1` when `return -1` is already below |
| line budget | hard cap, plus stopping where the model dedents out of your block |

Each is individually switchable in Settings.

Two of these are less obvious than they look:

**Code after the caret doesn't count if it's only `)]},;:`.** Completing an
argument inside `foo(⎸)` is genuinely useful, so closing brackets and separators
are treated as harmless. Only an identifier after the caret means you're editing
rather than writing.

**The line budget came from the logs, not from taste.** Every completion in one
session measured 410–427 characters — which is exactly the 128-token cap. The
model was running to the limit every time. Worse, each accepted suggestion grew
the prefix (379 → 789 → 1216 → 1328 chars) until the file was mostly the fizzbuzz
it had just written, so it wrote more fizzbuzz. The length cap and the echo rule
both address that directly.

Comment detection uses `PsiComment`, a genuine cross-language interface. String
detection matches on token-type names, which is a heuristic — it covers
Python/Java/Kotlin/JS/Go and will miss a language that names its string token
something unusual. Marked as such in the code.

---

## Telemetry, and what it found

Every suggestion appends one JSON object to
`inline-fim/suggestions.jsonl` in the IDE log directory. The record shape, with
the latency and context figures taken from a real logged request and the
accept/dismiss fields shown for illustration:

```json
{"ts":"2026-09-09T19:36:01Z","event":"accepted","finish_type":"SELECTED",
 "model":"qwen2.5-coder:1.5b-base","language":"Python","prefix_chars":1216,
 "suffix_chars":50,"suggestion_chars":426,"suggestion_lines":4,
 "ttft_ms":50,"total_ms":809,"cached":false,"truncated":true}
```

Dismissals carry the platform's `FinishType`, so *dismissed by pressing Escape* is
distinguishable from *dismissed because the editor lost focus* — a much weaker
negative signal. Silence decisions are logged too, with their reason, because how
often the tool stays quiet is as interesting as what it says when it speaks.

It records the **shape** of your code, never the content. No prompt text, no
completion text, no source. Nothing is uploaded. `bench/accept_rate.py` summarises
the file.

### What real sessions said

Three sessions in the sandbox by one person, each a few minutes of ordinary
Python. Sessions 2 and 3 each follow a fix that the previous session's telemetry
had exposed, so this is the log of a feedback loop, not three samples of one
thing. `bench/accept_rate.py` over the raw files, which are kept in
`bench/sessions/`:

| | 1 | 2 | 3 |
|---|---|---|---|
| suggestions shown | 12 | 27 | 25 |
| **accept rate** | **25.0%** | **55.6%** | **64.0%** |
| hit the line cap | 83.3% | 33.3% | 32.0% |
| stalls over 1s | 2 of 12 | 6 of 25 | 3 of 23 |
| worst TTFT | 4689ms | 4952ms | **2260ms** |
| TTFT p50, excluding stalls | 46ms | 176ms | **62ms** |
| total p50, excluding stalls | 1567ms | 638ms | **461ms** |

**n is 12, 27 and 25, by the author.** That is not an evaluation and the trend is
not evidence that the plugin got 2.5x better at writing code. Read it as what it
is: a record of three bugs being found and fixed, where the accept rate is the
least reliable column and the line-cap and stall columns are the ones that
actually moved for mechanical reasons.

What each session found:

1. 83% of suggestions were being cut off at the line cap, which made real p50
   total 1567ms against a benchmark's 210ms. It also left incomplete Python in
   the file, which broke the IDE's own indent-on-Enter -- a bug that looked like
   an editor problem and was ours.
2. Requests were queueing: Ollama's log showed durations climbing 2.4s to 7.2s
   across one burst of typing, because cancellation waited for a socket read
   that a queue had made slow. See [tests](#tests) for why the existing
   cancellation test could not have caught it.
3. No cascade left. Three stalls remain, all of them two requests overlapping
   rather than a pile-up.

### What these numbers do not capture

**An accepted suggestion that is then partly deleted still counts as a full
accept.** `onInsert` writes the record the moment Tab is pressed, and nothing
revisits it afterwards. So this is an *acceptance* rate, not a *retention* rate.

The distinction matters more than the gap in the numbers suggests. Accepting
four lines and immediately rewriting two of them is a much weaker endorsement
than accepting four and leaving them, and for training data the two should not
carry equal weight. Measuring it means watching the accepted range for a while
after insertion and recording how much of it survives -- a range marker and a
document listener, not a redesign. It is the first thing I would add.

Dismissals are also not all equal, and the record keeps the platform's
`FinishType` so they can be told apart later: `ESCAPE_PRESSED` is a deliberate
rejection, `INVALIDATED` means the user typed straight past it, and
`EDITOR_REMOVED` says almost nothing at all. Nothing downstream uses that
distinction yet.

---

## Tests

27, all runnable offline without Ollama.

Two of them are about cancellation, and the history of those two is the most
useful thing in this section.

**The first version was worthless.** Cancellation is easy to claim and easy to
break silently -- a blocking `send()` inside a coroutine, or a bare
`catch (e: Exception)` swallowing `CancellationException`, both leave code that
looks correct while requests run on to deliver results nobody wants. Neither
throws anything. So the test watches from the server side: a stub `HttpServer`
streams 40 tokens at 25ms intervals, the collector is cancelled after three, and
the test waits **longer than the entire stream would have taken** before checking
that the server never finished. That last part is the whole test. My first draft
waited 250ms against a 1000ms stream, which a completely broken implementation
would also have passed.

**Then the fixed version turned out to be too weak as well**, and only real
telemetry showed it. Streaming a token every 25ms means "aborts within one token"
and "aborts immediately" are indistinguishable -- and the difference between them
was, in production, the difference between 481ms and 7.2 seconds. The test passed
throughout.

So the second test reproduces what a queued server actually looks like: two quick
tokens, then a five-second stall, and cancellation must return in under a fifth
of that. It fails against the old implementation and passes against the new one.

Both were verified by mutation rather than by inspection. Setting
`onCancelling = false` makes the second fail; restoring it makes it pass.
Deleting the `body.close()` that does the aborting makes the first fail. The
negative control -- a stream that completes normally must not report itself as
cancelled -- passes in every case, so neither test is passing because the stub is
simply broken.

The lesson I would keep: a test that cannot fail is not a weak test, it is
decoration, and both of these looked completely reasonable while proving nothing.

**Three of the 27 came from a read-through rather than a failure.** The
completion cache was keyed on the context alone, so changing the model or the
line cap in Settings kept serving the previous setting's answers until the entry
aged out -- which presents as the setting being ignored, not as a cache bug.
A cache hit also reported `truncated = false` unconditionally, quietly
understating the line-cap rate that this README draws conclusions from. Both are
fixed, keyed now on context plus model plus cap, and mutation-verified the same
way: collapse the key back and two of the three go red.

---

## What I would do next

In the order I would actually do them.

**Measure retention, not just acceptance.** A range marker over the inserted text
plus a document listener, sampling how much of it survives a minute later. This is
first because it is the cheapest change that would make the accept rate mean
something, and because for finetuning it is the more honest label.

**Get it in front of someone who did not write it.** Every number here was
produced by the author. That is the confound no additional sampling by me can
remove.

**Explain the last three stalls.** They are no longer a cascade, but "two
overlapping requests" is a description. The suspect is the debounce firing a new
request before the previous one has finished tearing down; the telemetry needed
is a request id and a start timestamp, which is a few lines.

**Tune the context window and the line cap against the eval.** 3000/1000 and four
lines are reasoned, not optimised. The harness to sweep them exists. Tuning them
on a hunch is the mistake this project has otherwise avoided, and the eval is
cheap enough to answer both properly.

**An uncontaminated eval corpus.** Repositories published after the model's
training cutoff, so the Python number means what it appears to mean.

**Cross-file context.** Qwen2.5-Coder has `<|repo_name|>` and `<|file_sep|>`
tokens for repo-level FIM, so other open tabs could be fed in properly rather than
concatenated. Deliberately not built: speculative until the eval shows local
context is what limits quality.

**Stream the first line.** The whole completion is collected before anything is
shown, because trimming to a line budget and detecting an echo are judgements
about the whole suggestion and rendered text cannot be retracted. That costs the
difference between 62ms and 461ms at p50. Showing the first line as soon as it
completes, and the rest once judged, would recover most of it.

### What is weak

Ordered by how much they should change your reading of the numbers above.

- **One user, who is also the author.** This is the single biggest weakness in
  the project and no amount of extra sampling from me would fix it. I know what
  the model is good at, and I cannot un-know it while typing; every session is
  measured by someone with an interest in the result. A second person for ten
  minutes would be worth more than tripling my own sample, and I did not have
  one. Read every accept rate here with that attached.
- **Small n.** 12, 27 and 25 suggestions per session; 80 and 120 eval cases.
  Enough to catch a 5x latency regression, nowhere near enough to separate a 55%
  accept rate from a 64% one.
- **Acceptance, not retention.** Accepting a suggestion and then deleting half
  of it still counts as a full accept. See above for what it would take to fix.
- **Total p50 is 461ms in real use, against a 300ms goal.** Time to first token
  is well inside budget at 62ms, but nothing renders until the whole suggestion
  is in, so the number that matters is still over.
- **Three stalls per session are unexplained.** They are no longer the cascade
  that a queue caused -- Ollama's log shows two requests overlapping rather than
  a pile-up -- but "two overlapping requests" is a description, not a diagnosis.
- **The Python eval is contaminated** and cannot say by how much.
- **The 4-line cap is a guess.** Well-motivated by the logs, but 4 was never
  tested against 2 or 8. The telemetry could answer it and has not been asked.
- **String detection is a heuristic**, not a real language strategy.
- **Single-machine, single-platform numbers.** One Windows laptop with an RTX
  4060 throughout. The plugin has never been run on macOS or Linux, and the
  context-window reasoning would change materially on CPU-only hardware.
- **No manual-trigger shortcut.** The platform supports `ManualCall`; not wired.
- **`verifyPlugin` has never been run to completion** here -- it times out
  downloading an IDE to verify against. `buildPlugin` and
  `verifyPluginProjectConfiguration` both pass.

---

## A note on the Kotlin

I had not written any Kotlin before this project, and none of the JetBrains
platform. I learned both for it, so the code is commented more heavily than
production code should be — coroutines, `suspend`, receiver-scoped lambdas and the
platform's read-action model are all explained where they first appear.

Two things I got wrong on the way and fixed, both recorded above: swallowing
`CancellationException` in a general catch (found by reading the coroutines
contract, then pinned with a test), and writing a cancellation test whose
assertion couldn't fail.

The ML reasoning — FIM over chat, the context-window trade, building the eval
harness before trusting a feeling, and treating a one-case difference as noise —
is where I'd point first.
