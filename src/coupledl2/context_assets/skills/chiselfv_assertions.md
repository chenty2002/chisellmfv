# ChiselFV Assertions

Prefer the existing `chiselFv.Formal` API and native Chisel assertions already
used by the case. Assertions must be emitted into generated Verilog.

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

- `fvAssert(cond: Bool, msg: String = "")`
- `assertAt(n: UInt, cond: Bool, msg: String = "")`
- `assertAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = "")`
- `assertNextStepWhen(cond: Bool, asert: Bool, msg: String = "")`
- `assertAlwaysAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = "")`
- `astLiveness(req: Bool, resp: Bool, msg: String = "")`
- `astRelaxedLiveness(req: Bool, resp: Bool, n: Int, msg: String = "")`
- `assertLivenessTimer(cond: Bool, reset: Bool, n: Int, msg: String = "")`
- `assertMutex(conds: Seq[Bool], msg: String = "")`
- `assertOneHot(signal: UInt, msg: String = "")`
- `assertOneHot0(signal: UInt, msg: String = "")`
- `assertStable(signal: UInt, msg: String = "")`
- `assertStableWhen(en: Bool, signal: UInt, msg: String = "")`
- `assertOnRise(signal: Bool, cond: Bool, msg: String = "")`
- `assertOnFall(signal: Bool, cond: Bool, msg: String = "")`
- `assertImplies(antecedent: Bool, consequent: Bool, msg: String = "")`
- `assertImpliesDelay(antecedent: Bool, consequent: Bool, n: Int, msg: String = "")`

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
