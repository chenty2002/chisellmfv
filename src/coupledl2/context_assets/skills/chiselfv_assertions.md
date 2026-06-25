# ChiselFV Assertions

Use this skill when `build_contract.chisel.family` is `chisel6`, or when
`case/Chisel/build.sc` uses `org.chipsalliance::chisel`.

Assertions must be emitted into generated Verilog.

## Placement Rules

- First read the exact generated top and DUT source selected by the build
  contract.
- Add assertions directly inside the Chisel module/class that is emitted.
- If using ChiselFV, mix `with Formal` into that emitted class itself.
- Do not create an unused wrapper or sibling module such as `FooFormal`.
- Before completion, collect evidence that the generated Verilog contains the
  intended assertion labels.

## Property Selection

- Target architecturally meaningful CoupledL2 invariants and protocol progress.
- Avoid padding with trivial mirror checks or direct FSM restatements unless
  they guard a real failure mode.
- Prefer stable labels that can be traced in JasperGold reports and repair
  rounds.

## ChiselFV API Reference

Import:

```scala
import chiselFv._
```

Trait usage: add `with Formal` to the emitted DUT class, then place assertions
directly in that class body.

```scala
class VerifyTop extends Module with Formal {
  val io = IO(...)
  fvAssert(property, "property_name")
}
```

Common APIs in trait `Formal`:

```scala
def fvAssert(cond: Bool, msg: String = ""): Unit
def assertAt(n: UInt, cond: Bool, msg: String = ""): Unit
def assertAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = ""): Unit
def assertNextStepWhen(cond: Bool, asert: Bool, msg: String = ""): Unit
def assertAlwaysAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = ""): Unit
def astLiveness(req: Bool, resp: Bool, msg: String = ""): Unit
def astRelaxedLiveness(req: Bool, resp: Bool, n: Int, msg: String = ""): Unit
def assertLivenessTimer(cond: Bool, reset: Bool, n: Int, msg: String = ""): Unit
def assertMutex(conds: Seq[Bool], msg: String = ""): Unit
def assertOneHot(signal: UInt, msg: String = ""): Unit
def assertOneHot0(signal: UInt, msg: String = ""): Unit
def assertStable(signal: UInt, msg: String = ""): Unit
def assertStableWhen(en: Bool, signal: UInt, msg: String = ""): Unit
def assertOnRise(signal: Bool, cond: Bool, msg: String = ""): Unit
def assertOnFall(signal: Bool, cond: Bool, msg: String = ""): Unit
def assertImplies(antecedent: Bool, consequent: Bool, msg: String = ""): Unit
def assertImpliesDelay(antecedent: Bool, consequent: Bool, n: Int, msg: String = ""): Unit
```

These ChiselFV APIs accept Chisel `Bool` conditions, not LTL formulas.

Do not place assertions inside `when` blocks. Encode the guard into the
property instead:

```scala
fvAssert(!enable || property, "guarded_property")
```

For liveness checks, integrate the active condition into request and response:

```scala
astRelaxedLiveness(active && request_valid, done || !active, 1000, "progress")
```

## Chisel 6 BoringUtils Reference

For Chisel 6 CoupledL2 cases, use the one-argument bore form when exposing an
internal signal as a local value:

```scala
val signal = BoringUtils.bore(source)
```

For named cross-module wiring, use explicit source/sink names:

```scala
BoringUtils.addSource(source, "stateArray_0", disableDedup = true)
BoringUtils.addSink(sink, "stateArray_0")
```

Do not use the Chisel 3-only `BoringUtils.bore(source, Seq(sink))` helper style
unless the selected skill is `chiselfv_chisel3_assertions.md`.

## Chisel 6 LTL Assertion API Reference

Imports:

```scala
import chisel3.ltl._
import chisel3.ltl.Sequence._
```

Core sequence patterns:

```scala
val seq1: Sequence = mySignal
seq.delay()
seq.delay(3)
seq.delayRange(2, 5)
seq.delayAtLeast(3)
seq1.concat(seq2)
seq1 ### seq2
seq1 ##* seq2
seq1 ##+ seq2
seq1.and(seq2)
seq1.or(seq2)
```

Core property patterns:

```scala
prop.not
prop.eventually
prop1.and(prop2)
prop1.or(prop2)
```

Use `AssertProperty` directly in the emitted module:

```scala
AssertProperty(mySignal)
AssertProperty(mySignal, "label_name")
AssertProperty(request |-> Sequence(grant).delay(1, 10), None, None, Some("request_grant"))
```

Do not use `Some(...)` for simple Bool assertion labels:

```scala
// Wrong
AssertProperty(mySignal, Some("label_name"))

// Right
AssertProperty(mySignal, "label_name")
```

As with ChiselFV, do not wrap `AssertProperty` in `when`; encode the condition
inside the property:

```scala
AssertProperty(!enable || prop)
AssertProperty(enable |-> prop)
```
