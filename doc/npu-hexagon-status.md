# NPU（Hexagon HTP）路线状态

设备：小米 15（24129PN74C），SM8750 骁龙 8 Elite，Android 16，**Hexagon v79**。
模型：`Qwen3.5-2B-Q4_0.gguf`（1.21GB）+ `mmproj-F16.gguf`（668MB）。

## 结论：NPU 能在真机上跑，且 prefill 有 3.6 倍加速；decode 反而更慢

实测（每次 128 token prompt / 32 token 生成，各 3 次）：

| 后端 | Prefill (tok/s) | Decode (tok/s) |
| --- | --- | --- |
| CPU (`ngl=0`) | 107.9 – 120.3（均值 ~113） | 22.5 – 24.4（均值 ~23.5） |
| **NPU (`ngl=99`)** | **354.1 – 452.0（均值 ~415）** | 13.5 – 14.9（均值 ~14.0） |
| 比值 | **NPU 快 3.6×** | **NPU 慢 1.7×** |

**原因**：prefill 是算力受限（NPU 的 HVX/HMX 占优），decode 是权重带宽受限
（CPU 的内存子系统占优，NPU 的算力优势用不上）。这与第三方在同款 SM8750 上的
独立测量完全一致（他们测出 prefill 5.1× 快、decode 1.5× 慢）。

**对本项目的实际意义**：相册 App 是 **prefill 密集型**——每张照片都要先编码成
上千个视觉 token 再喂给 LLM。所以 NPU 恰好命中真正的瓶颈，即使 decode 变慢。

## 设备侧证据（真实日志）

```
ggml-hex: Loading driver libcdsprpc.so
ggml-hex: Hexagon backend (experimental) : allocating new registry : ndev 1
ggml-hex: Hexagon Arch version v79
ggml-hex: HTP0 allocating new session
ggml-hex: HTP0 hwinfo: threads 6, hvx 6, hmx 1, vtcm 8 MB
ggml-hex: HTP0 new session : session-id 0 domain-id 3
          uri file:///libggml-htp-v79.so?htp_iface_skel_handle_invoke&_modver=1.0&_dom=cdsp&_session=0
ggml-hex: HTP0 op batching: n-bufs 16 n-tensors 7168 n-ops 1024 vmem 3355443200
```

注册表枚举确认：

```
registry entries: 2
  [0] HTP   devices=1   - HTP0  type=1   ← NPU
  [1] CPU   devices=1   - CPU   type=0   mem=15113 MiB
```

## 两个原先标记为高风险的问题，实测都已解决

1. **skel 可发现性**（原研究标记为"最高未知风险"）
   → **解决**。`ADSP_LIBRARY_PATH` 指向 `/data/local/tmp/hex/backends` 时，
   FastRPC 守护进程能成功加载 `libggml-htp-v79.so`，会话正常建立。

2. **`libcdsprpc.so` 能否被非 APK 进程访问**
   → **解决**。一个纯 CLI 进程（非 APK、无任何 manifest 声明）`dlopen` 成功：
   ```
   OK    dlopen(libcdsprpc.so) [by soname]
   OK    dlopen(/vendor/lib64/libcdsprpc.so) [by absolute path]
   ```
   说明 Android 16 / MIUI 在这台设备上并未对该 vendor 库做 namespace 限制。

## 工具链构建路径（可复现）

本机 **Docker Desktop 未运行**，因此走 **WSL2 Ubuntu 原生构建**（Hexagon 工具链
本就是 Linux 二进制，比 Docker 更直接）。

```bash
# 1) Hexagon SDK 6.6.0.0（公开 GitHub 版本，非账号门控）
curl -L -o hexagon-sdk.tar.xz \
  https://github.com/snapdragon-toolchain/hexagon-sdk/releases/download/v6.6.0.0/hexagon-sdk-v6.6.0.0-amd64-lnx.tar.xz
# sha256 = 4a916e42c1dab9efdf2e58773f901ea780fa43c907bf054ab76572a3b3d942f4
tar -xf hexagon-sdk.tar.xz     # SDK 根: 6.6.0.0/
                               # HEXAGON_TOOLS_ROOT: 6.6.0.0/tools/HEXAGON_Tools/19.0.07

# 2) Android NDK r28b（Linux 版）
curl -L -o ndk.zip https://dl.google.com/android/repository/android-ndk-r28b-linux.zip

# 3) configure + build（见 build-models/hex-configure.sh 与 hex-build.sh）
```

### 途中修掉的三个真实问题

1. **NDK 解压损坏**：`python zipfile` / `unzip` 不还原 Unix 权限位和符号链接，
   导致 `clang`（内容是 `clang-19` 的包装脚本）无法工作。
   → 见 `build-models/extract-ndk.py`，按 zip 的 `external_attr` 重建 35 个符号链接、
   恢复 1640 个可执行位。

2. **`clang` 包装脚本需要 `clang-19` 在 PATH 中**
   → `build-models/hexenv.sh` 把 NDK bin 与 Hexagon Tools bin 都加进 PATH。

3. **SDK `hexagon_fun.cmake:104` 在 host-only 配置下崩溃**
   （`PREBUILT_LIB_DIR` 为空 → `string(FIND` 参数不足）
   → 见 `build-models/patch-hexagon-sdk.py`，已加空值防护并保留 `.orig` 备份。

## 产物

`build-models/npu/`（未提交 Git，见 `.gitignore`）：

| 文件 | 大小 | 用途 |
| --- | --- | --- |
| `libggml-hexagon.so` | 5.97 MB | host 侧 NPU 后端（分发 ggml 算子到 DSP） |
| `libggml-htp-v79.so` | 606 KB | **本设备**的 DSP skel（SM8750 是 v79） |
| `libggml-htp-v73/v75/v81.so` | 各 ~600 KB | 其它骁龙的 skel，保持可移植性 |

## 尚未完成：接入 App

CLI 验证已完成，但**尚未集成进 Android App**。集成需要解决：

1. **后端发现机制**：实测 `GGML_BACKEND_PATH` 环境变量是可靠通道
   （`ggml_backend_load_all()` 只搜编译期 `GGML_BACKEND_DIR`、可执行文件目录与 cwd，
   在 App 里都不可控）。但 App 无法设置环境变量，因此需要在 JNI 里显式调用
   `ggml_backend_load(path)` 或 `ggml_backend_load_all_from_path(dir)`。
2. **skel 分发**：skel 必须让 FastRPC 守护进程找到。CLI 用 `ADSP_LIBRARY_PATH`，
   App 里同样设不了环境变量 —— 这是**集成阶段的主要未解问题**，需要实测
   `libggml-htp-v79.so` 放进 APK 的 `jniLibs/arm64-v8a/` 后能否被加载。
3. **Q4_0 是硬要求**：HTP 的 matmul 只接受 Q4_0/Q4_1/Q8_0/IQ4_NL/MXFP4/F16/F32。
   当前主模型已是 Q4_0（`QwenModel.MODEL_FILE_CANDIDATES` 首选即为它）。
4. **视觉塔只能留在 CPU**：本 checkout 的 hexagon 后端没有 IM2COL/卷积算子，
   `ggml_conv_2d`（patch embedding）无法上 NPU。上游 PR #26007 已实现 IM2COL，
   升级 llama.cpp 后可再评估。
5. **不支持量化 KV cache**：HTP 上 `-ctk q8_0` 会 `GGML_ASSERT` 中止。

## 复现命令

```bash
# 设备侧基准（prefill/decode 分离，CPU vs NPU）
adb shell sh /data/local/tmp/hex/repeat-bench.sh
```
