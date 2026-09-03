# Android multi-brand builds

The project builds the same Meta core and cloud business code for three brands.

| Brand flavor | Product | Application ID | Release task |
| --- | --- | --- | --- |
| `zy` | ZY | `com.flutterfly.zhuiyun` | `:app:assembleZyMetaRelease` |
| `fly` | Fastfly | `com.flutterfly.fastfly` | `:app:assembleFlyMetaRelease` |
| `carx` | CarX | `com.zz.car` | `:app:assembleCarxMetaRelease` |

Debug builds use the corresponding `*MetaDebug` task. Brand-specific Android
launcher resources live under `app/src/<brand>/res`; runtime URLs and identity
values are declared in the matching brand flavor in the root
`build.gradle.kts`.
