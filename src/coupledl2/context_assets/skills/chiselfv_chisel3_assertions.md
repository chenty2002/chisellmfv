# Chisel3 ChiselFV Assertions

Use this skill when `build_contract.chisel.family` is `chisel3`, or when
`case/Chisel/build.sc` uses `edu.berkeley.cs::chisel3`.

## Required Compatibility Checks

- Read `case/Chisel/build.sc` or `case/Chisel/common.sc` before choosing APIs.
- Use the API rules in this skill for Chisel 3 cases. Do not infer Formal or
  BoringUtils semantics from source snippets.

## Forbidden Chisel 6 APIs

- Do not import or use `chisel3.ltl._`.
- Do not use `AssertProperty`, `Sequence`, `|->`, `.eventually`, or
  `.delayRange`.
- Do not call `fvAssert`.
- Do not use one-argument `BoringUtils.bore(source)`.

## Assertion Style

For Chisel 3 CoupledL2 cases, use these `Formal` APIs:

```scala
def assert(cond: Bool, msg: String = ""): Unit
def assertAt(n: UInt, cond: Bool, msg: String = ""): Unit
def assertAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = ""): Unit
def assertNextStepWhen(cond: Bool, asert: Bool, msg: String = ""): Unit
def assertAlwaysAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = ""): Unit
def past[T <: Data](value: T, n: Int)(block: T => Any): Unit
def anyconst(w: Int): UInt
def assertLivenessTimer(cond: Bool, reset: Bool, n: Int, msg: String = ""): Unit
```

Do not use `fvAssert`, `astLiveness`, or `astRelaxedLiveness` in Chisel 3
cases.

Encode guarded assertions as Boolean implications:

```scala
assert(!active || property, "property_name")
```

For liveness-like checks, use counters or the local timer helper instead of LTL:

```scala
assertLivenessTimer(waiting, done || !active, 1000, "progress_timer")
```

Do not place the assertion call inside a `when` block unless the local `Formal`
implementation already handles that exact pattern. Prefer to encode the guard in
the Boolean condition.

## BoringUtils Style

For Chisel 3 CoupledL2 cases, use explicit source/sink names:

```scala
BoringUtils.addSource(source, "stateArray_0", disableDedup = true)
BoringUtils.addSink(sink, "stateArray_0")
```

When a local helper is necessary, implement it with the two-argument sink form:

```scala
def boreOut[T <: Data](source: T): T = {
  val sink = WireDefault(0.U.asTypeOf(chiselTypeOf(source)))
  BoringUtils.bore(source, Seq(sink))
  sink
}
```

Do not mix Chisel 6 one-argument `BoringUtils.bore(source)` examples into a
Chisel 3 case.

## Completion Evidence

Before calling `complete_stage`, inspect the edited source and generated
Verilog. The completion summary should cite:

- the detected Chisel family/version source;
- the versioned skill API methods used;
- the assertion labels added;
- evidence that generated Verilog contains the expected assertion constructs.
