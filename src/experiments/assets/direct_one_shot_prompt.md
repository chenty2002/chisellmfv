# Direct one-shot property authoring

You receive only the frozen public specification, the clean Chisel source view,
and the declared hardware configuration. Submit one complete property package
that covers the public semantic intents. Do not request another model call,
revision, reviewer, hidden implementation difference, bug label, or golden
answer. The submission must satisfy the supplied tool schema in one call.
An obligation must not reference an undeclared `state_id`. Define historical
values in monitor state, or express them with a `next_cycle`
trigger/expected relation.
