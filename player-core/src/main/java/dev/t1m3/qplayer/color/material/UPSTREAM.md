# Google Material Color Utilities

Source: https://github.com/material-foundation/material-color-utilities

Pinned revision: `9889de141b3b5194b8574f9e378e55f4428bdb5e` (Java 8 compatible).

Apache License 2.0; see LICENSE and each source file's copyright notice. A copy is also shipped in the Android APK under assets/licenses/Material-Color-Utilities-Apache-2.0.txt.

The Java sources provide Google's HCT/CAM16 solver, tonal palettes, Expressive scheme, Material dynamic color roles and contrast resolution. QPlayer does not substitute its old LCh approximation for this implementation.

Only integration changes: package/import names moved below `dev.t1m3.qplayer.color.material`; compile-time-only `NonNull`, `Nullable` and Error Prone `Var` annotations removed to avoid extra Android-only dependencies in the shared core. Color calculations are unchanged.

`MaterialExpressivePalette` selects `SchemeExpressive` at standard contrast (0.0). Expressive intentionally rotates the primary hue from the source hue; the secondary, tertiary and neutral palettes also follow Google's scheme. Light/dark roles, fixed roles, text/container pairs and error colors come directly from `DynamicScheme`.
