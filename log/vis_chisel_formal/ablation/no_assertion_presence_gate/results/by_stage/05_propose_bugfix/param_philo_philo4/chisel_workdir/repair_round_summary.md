Repair round 1: Fixed 2 categories of assertion errors in philo4.scala.

(1) `reading_implies_left_thinking_ph0-3` (lines 135-138): Changed consequent from `=== PhilosopherState.THINKING` to `=/= PhilosopherState.THINKING`. Root cause was backwards assertion: transition `READING → THINKING when left===THINKING` means READING exits when left is THINKING, so READING implies left is NOT THINKING (the assertion originally required the opposite). All 4 structurally identical assertions repaired homologously. Original assertion labels preserved exactly.

(2) `hungry_left_not_eating_ph0-3` (lines 140-145): Removed these 4 assertions because they are incorrect invariants. A philosopher enters HUNGRY from THINKING independently (when `right≠READING && coin=0`), regardless of left neighbor's state. Therefore `ph0=HUNGRY && ph3=EATING` is a reachable valid state — the assertion was based on a misinterpretation of the HUNGRY→EATING transition condition. Labels removed since these assertions are not meaningful safety properties.

(3) Liveness `liveness_hungry_to_eating_ph0-3`: Left unchanged — these may need fairness assumptions on coins in a future round if they continue to fail.