# Espresso source and build

The source under `espresso/` was imported from
[oracle/graal](https://github.com/oracle/graal) at tag `vm-25.5.5.0.3`,
commit `e3cb74fceaf6ee4f50366d5ab2bbf602e464475d`.
See `espresso/LICENSE` and the notices in individual files.

The Maven reactor builds the Espresso annotation processor and the JVM with
its EspressoLibs implementation. The runtime supplies its own extensions
through service providers. Truffle, Polyglot, Espresso shared code and the
published EspressoLibs resources remain version-matched Maven dependencies.
