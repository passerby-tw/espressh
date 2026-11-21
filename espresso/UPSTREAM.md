# Espresso source and build

The source under `espresso/` was imported from
[oracle/graal](https://github.com/oracle/graal) at tag `vm-25.5.5.0.3`,
commit `e3cb74fceaf6ee4f50366d5ab2bbf602e464475d`.
The [Espresso JVM license](espresso/LICENSE) is GPL version 2. Individual
source files and bundled components carry their own license notices.
Espressh-authored code is licensed under the repository-level MIT license.
The combined runtime distribution is licensed under GPL-2.0-only. See the
[repository licensing section](../README.md#license) for distribution scope.

The Maven reactor builds the Espresso annotation processor and the JVM with
its EspressoLibs implementation. The runtime supplies its own extensions
through service providers. Truffle, Polyglot, Espresso shared code and the
published EspressoLibs resources remain version-matched Maven dependencies.
