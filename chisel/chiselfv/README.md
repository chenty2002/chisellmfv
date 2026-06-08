# ChiselFV2
A Formal Verification Framework for Chisel, an enhanced version of [ChiselFv](https://github.com/Moorvan/ChiselFV).

## Usage

Most of the usage is identical to the original version of ChiselFV. For the module under test, inherit this module using `with Formal`, then all the assertion interfaces in ChiselFV2 can be used within this module. To compile and verify, use one of the invocation methods within the top-level module:

```scala
  Check.jg(duv_module: () => T)
  Check.bmc(duv_module: () => T, depth: int)
  Check.kInd(duv_module: () => T, depth: int)
  Check.pdr(duv_module: () => T, depth: int)
```

The parameter `duv_module` is a function that returns the module of the design under verification. The parameter `depth` is the depth of the BMC, K-Induction, and PDR algorithms. The default value is 20.

Note: to use `Check.jg` for verification you have to install and configure JasperGold.

## Motivation

In Chisel v3.6.0, the native verification syntax only includes bare assertions such as $\mathtt{assert}$ and $\mathtt{assume}$, which are limited to signals within one single cycle. Although Chisel has introduced more LTL and CTL verification primitives in v6.5.0\cite{chisel_v6.5.0}, it is cumbersome for large projects to transfer to the latest version and time-consuming for contributors to write efficient LTL assertions.
Besides, conventional testing techniques struggle to locate intricate errors, especially deadlocks.
An assertion interface for the liveness property is required to enhance the verification efficiency and coverage of Chisel projects. 

Conventional formal methods involve writing property assertions using the System Verilog Assertion (SVA) when faced with liveness properties. However, such defined liveness properties are written only in Verilog, which is inflexible for Chisel. In addition, using $\textbf{eventually}$ to check infinite cycles, this definition suffers from the state explosion problem and is unfriendly to model checking algorithms. Such methods could result in lower convenience and readability in agile development.

## Methodology

In practice, we approximate corresponding liveness properties with finite-step safety properties, which describe a request-response model within the specified time range. Based on engineering practice, if the system fails to satisfy the property over a long period, there is a high likelihood that a deadlock has occurred. A cyclic wait condition can be identified by examining the resource allocation required for each request and the dependency relationships between requests.  
The advantage of doing this is that safety verification is much simpler than liveness verification, thereby improving verification efficiency and compressing the state search space and the length of counterexamples.
Following such insight, we add a more sophisticated assertion to ChiselFV to verify liveness properties.

```scala
  astLiveness(req: Bool, resp: Bool, n: Int)
```

The assertion astLiveness can be invoked with two Chisel conditions and a timer limit. This assertion uses a register that counts the cycles since $\mathtt{req}$ becomes true. Whenever $\mathtt{resp}$ becomes true, the timer resets, and when the timer exceeds $\mathit{n}$, the property is violated. It describes a property that from the moment $\mathtt{req}$ becomes true, $\mathtt{resp}$ should become true within $\mathit{n}$ cycles.

Given the model's properties, the relevant assertions and the system's SystemVerilog codes can be generated automatically with one click. The model checking phase can be configured to use one of the four tools/algorithms, and the other scripts and configurations are pre-defined.

```scala
    Check.jg(duv_module: () => T)
    Check.bmc(duv_module: () => T, depth: int)
    Check.kInd(duv_module: () => T, depth: int)
    Check.pdr(duv_module: () => T, depth: int)
```

ChiselFV invokes the Chisel compilation process to generate SystemVerilog and embeds the verification codes. This way, all verification interfaces can be seamlessly integrated into Chisel projects and parsed by SystemVerilog formal tools, bridging the gap in the Chisel-to-Verilog toolchain.

## Formal Tool Integration

Formal verification tools are crucial for identifying circuit errors. After exporting the SystemVerilog code of the system under verification, formal tools for Verilog can be utilized. Commonly used formal tools for Verilog include open-source software such as ABC from UCB, Pono from Stanford University, and the integrated platform Yosys, as well as commercial software like JasperGold from Cadence and VC Formal from Synopsys.

ChiselFV has been adapted to invoke the open-source tool Yosys and can automatically generate the corresponding scripts for selected algorithms, including Bounded Model Checking (BMC), K-Induction, and Property Directed Reachability (PDR). However, existing algorithm strategies struggle to tackle large-scale complex systems and lack comprehensive support for hardware verification. 

We add support for JasperGold to ChiselFV, allowing developers to select formal tools at the verification interface. Newly added support offers a collection of specialized engines and more detailed configuration options, making it more suitable for formally verifying the Verilog code.

The formal tool takes the exported SystemVerilog code as input. Once verified by various algorithms, these RTL codes (usually transformed into netlists) can yield results indicating whether the system satisfies (Proof) or violates the properties (CEX). For a property violation, the corresponding waveform can also be exported. Compared to simulation-based verification, the waveform of a counterexample is often much shorter, which is beneficial for error detection and repair.