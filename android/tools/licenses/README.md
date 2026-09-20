# 许可清单维护说明

`android/app/src/main/assets/licenses/manifest.json` 是许可链路的唯一清单来源：

- About 页从这个 asset 读取条目、版权/归属声明和完整许可证正文；不要在 Kotlin UI 中再维护依赖列表。
- `:app:verifyBundledLicenses` 作为 `preBuild` 前置门禁，直接核对实际解析的 Debug/Release runtime dependency graph 与 manifest 中已审核的精确版本快照；新增、删除或改版均要求复核，不能只靠 group 通配符绕过；结果写入 `app/build/reports/licenses/runtime-dependencies.txt`，不依赖开发者临时日志。
- `scripts/release/test_licenses.py` 校验 manifest、Android 声明依赖、JS `package-lock.json`、bundle asset 和打包排除策略。
- `android/tools/sdk/build.mjs` 从 esbuild metafile 提取实际进入 `music-sdk.js` 的 `node_modules` 包，并拒绝未在 manifest 中覆盖的包。
- APK 的 `META-INF/{AL2.0,LGPL2.1}` 通用资源排除不承担许可归档职责；完整正文和声明随 `assets/licenses/manifest.json` 进入 APK。SDK bundle 的 `legalComments: none` 同样不影响这份离线归档。

## 更新步骤

1. 修改 Android 依赖版本或增加新的依赖组后，先取得新的 `debugRuntimeClasspath`（或等价的 release runtime）报告；不要凭记忆补许可证。
2. 以实际版本的 POM、AAR/JAR 内 `LICENSE`/`NOTICE`、vendored 源码 LICENSE 和 JS 包内 LICENSE/README 为 primary 证据，更新同一份 manifest。
3. 对 QuickJS、vendored `musicSdk`、bundle 运行时 JS 包和 build-only 工具分别保持 scope；build-only 工具可以校验但不应冒充 APK 运行时依赖。
4. 运行 `python3 scripts/release/test_licenses.py`，再运行 `node --check android/tools/sdk/build.mjs`。不需要也不应在这条轻量校验中启动 Gradle。
