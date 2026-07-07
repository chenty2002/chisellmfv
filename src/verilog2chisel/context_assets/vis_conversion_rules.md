# VIS Verilog to Chisel/Formal Conversion Rules

You are converting VIS-style symbolic Verilog into Chisel for formal verification.
Preserve the behavior represented by the supplied Verilog source exactly.
Do not infer benchmark-specific fixes, external bug labels, README facts, or design intent that is not present in the Verilog text.

## Output contract

Generate complete Scala/Chisel source files in package `llmverify`.
Every generated file must compile with Chisel 6.7.0 and Scala 2.13.
Create exactly one `object VerilogGenerator extends App` that calls `emitVerilog(new <TopModule>(), args)`.
Do not use unsupported Scala destructuring patterns such as:

```scala
val a :: b :: c :: Nil = Enum(3)
```

Prefer explicit UInt constants for Verilog enum-like values:

```scala
object Command {
  val width = 4.W
  val state_a = 0.U(width)
  val state_b = 1.U(width)
  val state_c = 2.U(width)
}
```

## VIS `$ND(...)` semantics

Treat every Verilog `$ND(...)` as a formal-verification nondeterministic free variable.
In Chisel, do not model `$ND(...)` with an LFSR, `RegInit`, pseudo-random logic, a fixed constant, or an ordinary state register.
Prefer modeling nondeterminism as unconstrained harness-level `Input`s so the formal engine may choose any value on every cycle.
Then constrain the value to the legal set with `assume` when the set has more than the natural Boolean domain.

Examples:

```verilog
assign x = $ND(0, 1);
```

Use:

```scala
val nd_x = IO(Input(Bool()))
x := nd_x
```

For enum-valued nondeterminism:

```verilog
assign cmd = $ND(state_a, state_b, state_c);
```

Use an unconstrained UInt input and assume it is in the legal set:

```scala
val nd_cmd = IO(Input(UInt(Command.width)))
assume(nd_cmd === Command.state_a || nd_cmd === Command.state_b || nd_cmd === Command.state_c)
cmd := nd_cmd
```

Use `assume(...)` directly with the standard Chisel imports when it compiles in this environment.
Do not import `chisel3.experimental.verification._`; this Chisel 6.7.0 setup does not provide that package.
If a repair turn cannot compile `assume(...)`, use `assert(...)` only as a compile-compatible formal constraint and keep the free variable as an `IO(Input(...))`.

If the Verilog semantics are "arbitrary initial value, then updated by logic later", use arbitrary initial state supported by the formal flow. Do not replace it with a fixed `RegInit` value unless the Verilog has an explicit deterministic initial assignment.
For Chisel registers that should be arbitrary initially, prefer resetless `Reg(...)` plus normal next-state updates.

## Initial blocks

Preserve deterministic Verilog `initial` assignments with Chisel reset initialization only when the Verilog explicitly fixes the value.
Do not silently "improve" suspicious-looking initialization or range logic. If the Verilog assigns or compares a value in a way that seems unreachable, translate that source behavior faithfully.

## Arrays and ranges

VIS Verilog may use ranges such as `[0:3]`, descending ranges, and arrays indexed from 1.
Preserve the logical index domain even if Chisel uses `Vec`.
When translating arrays such as `locations[1:2]`, either allocate an extra unused index 0 or remap indexes consistently and document the mapping in comments.
If a packed vector is assigned one bit at a time, do not model the writable internal value as `UInt` and then assign `x(0) := ...`; Chisel bit-selects of `UInt` are read-only.
Use `Wire(Vec(n, Bool()))` or `Reg(Vec(n, Bool()))` for per-bit writes, then convert at module boundaries with `VecInit(bits).asUInt` or assign individual IO bits from the Vec.
Every Chisel `Wire`, `Wire(Vec(...))`, and output element must be fully initialized on all paths.
When preserving one-based Verilog indexes by allocating an unused index 0, assign the unused element a default such as `false.B` or `0.U`.
Prefer `WireDefault(VecInit(Seq.fill(n)(false.B)))` for Boolean Vec wires and assign all IO output Vec elements before conditional logic.

## Blocking assignments

Most VIS models use blocking assignments inside clocked `always @(posedge clk)` blocks.
Translate each clocked block as a synchronous next-state update. Preserve the intended state update order when multiple assignments target related registers in the same block.
Avoid creating combinational loops.

## Source-only fidelity

The only design-specific semantics available to you are those in the supplied Verilog source and the deterministic source summary.
Do not use benchmark names, external labels, or known-bug descriptions to change the translation.
If a source construct appears buggy, preserve it unless the Verilog itself implements a corrected behavior.

## Forbidden patterns

Do not emit:

- LFSR or pseudo-random modules for `$ND`;
- `scala.util.Random`;
- fixed constants in place of `$ND` choices;
- `RegInit` for nondeterministic free variables;
- invalid Chisel enum destructuring with `:: Nil = Enum(...)`;
- assigning to `UInt` bit-select expressions such as `x(0) := y`;
- leaving unused Vec entries or IO output bits uninitialized;
- benchmark-specific repairs or comments based on information outside the Verilog source;
- placeholder modules or incomplete bodies;
- generated Chisel that drops top-level observable behavior.
