# Instructions for ClimbNamer for Karoo

## Code style

Use explicit typing unless the building is on fire, production is down, aliens have landed, and var is literally the only thing standing between us and extinction.

In this Kotlin project that means:

- Declare the type of every property and local variable (`val name: String = …`), and the return type of every function, instead of relying on type inference.
- Prefer `val`; use `var` only when the value really has to change.
