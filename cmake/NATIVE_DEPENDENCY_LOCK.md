# Native Dependency Lock

Pinned commit SHAs for all native dependencies fetched at CMake configure time.
Resolved on 2026-07-22 and verified by a full Nightly build.

| Dependency | Repository | Pinned SHA | Previously tracked ref |
|---|---|---|---|
| sherpa-ncnn | https://github.com/k2-fsa/sherpa-ncnn.git | `c61e50d61e9fbed5972afa4d95bc560e168affe2` | master |
| ncnn | https://github.com/Tencent/ncnn.git | `a4d2ea1d4422c9e849f166fd7a4aefb52f942f6a` | master |
| WAMR | https://github.com/bytecodealliance/wasm-micro-runtime.git | `2ae370d2219b56fa294294b3dae67e588c431eae` | main |
| QuickJS | https://github.com/bellard/quickjs.git | `04be246001599f5995fa2f2d8c91a0f198d3f34c` | master |

## Why pin

### Speech AAR

`org.k2fsa:sherpa-onnx-static-link-onnxruntime:1.13.8@aar` is fetched from the official
[v1.13.8 release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8).
SHA-256: `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471`.
Size: 38,691,998 bytes. Gradle `verifySherpaRuntime` runs before `preBuild`.
The AAR has arm64-v8a, armeabi-v7a, x86 and x86_64; the app currently selects arm64-v8a.
All inspected ELF load segments have 16 KiB alignment. This does not certify the entire APK or
other native dependencies. Unlike the ARM and x86_64 variants, x86 includes a separate ORT library.
Model revision and license details are in [voice-modernization.md](../docs/agent/voice-modernization.md).

- Reproducible builds: the same Operit commit always compiles identical native source.
- Stable ccache hit rates: upstream ref movement no longer triggers mass recompilation.
- Build integrity: no sudden build breakage from unrelated upstream changes.

## How to upgrade

1. Resolve the new SHA with `git ls-remote <repo> refs/heads/<branch>` or `refs/tags/<tag>`.
2. Update the SHA in the corresponding `CMakeLists.txt` / `*.cmake` file.
3. Update this manifest.
4. Run a full build and verify the affected module still compiles and passes device smoke tests.
5. Upgrade dependencies individually, not in bulk, so failures are attributable.
