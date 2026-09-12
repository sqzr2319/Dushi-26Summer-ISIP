# NPU 接入 Android App —— 集成状态（已打通）

承接 `npu-hexagon-status.md`（CLI 验证已完成）。本文记录把它接进 App 的结果。

> **状态更新：NPU 已经在 App 内可用，并且是生产路径的首选后端。**
>
> 本文档此前记录的是"集成链路已打通，但最后一步被平台限制卡住"
> （`AEE_EUNABLETOLOAD 0x80000406`，会话建不起来）。那个限制**已经解决** ——
> 根因不是平台不允许，而是 `ADSP_LIBRARY_PATH` 需要在**进程内、FastRPC 首次初始化
> 之前**设置，App 只是"设不了环境变量"，但可以 `setenv()`。
>
> 当前生产配置：`backend = AUTO` ⇒ **NPU 优先，失败自动回落 CPU**。

## 结论摘要

| 环节 | 状态 |
| --- | --- |
| 交叉编译 NPU 后端（host 库 + DSP skel） | ✅ 完成 |
| 模块随 APK 分发 | ✅ 完成 |
| 模块在 App 进程内加载 | ✅ 完成 |
| vendor 库 `libcdsprpc.so` 可见 | ✅ 解决（manifest 声明） |
| 识别 NPU 硬件 | ✅ `Hexagon Arch version v79` |
| **建立 DSP 会话** | ✅ **已解决**（进程内 `setenv("ADSP_LIBRARY_PATH")`） |
| **NPU 上跑出正确输出** | ✅ **已验证**（真实相册照片，见下） |
| 生产路径默认启用 NPU | ✅ `backend = AUTO` 即 NPU 优先 |
| App 核心功能 | ✅ CPU 兜底始终可用 |

## 关键修复：`ADSP_LIBRARY_PATH` 必须进程内设置

FastRPC 守护进程按**文件名**（URI 是 `file:///libggml-htp-v79.so?...`，只有名字没有
路径）在 `ADSP_LIBRARY_PATH` 里解析 DSP skel。App 设不了环境变量 —— 这是此前认定为
"平台限制"的原因。

**但 `setenv()` 是进程内的，App 完全可以调用。** 要点是**顺序**：

1. 必须先 `setenv("ADSP_LIBRARY_PATH", nativeLibraryDir)`；
2. 再加载 `libggml-hexagon-0.so`（这一步会打开 CDSP 会话、解析 skel）。

一旦顺序反了，会话就已经用旧的（空）搜索路径尝试过，之后再设也没用 —— 症状正是
`AEE_EUNABLETOLOAD 0x80000406`。

对应实现：`llama_jni.cpp` 的 `nativeSetAdspLibraryPath`，由 Kotlin 侧
`LlamaCppWrapper.loadAcceleratorBackends()` 在 `nativeLoadBackendsFrom()` **之前**调用。

跑通后的原生日志：

```
ADSP_LIBRARY_PATH=/data/app/~~.../lib/arm64
ggml-hex: HTP0 allocating new session
ggml-hex: HTP0 hwinfo: threads 6, hvx 6, hmx 1, vtcm 8 MB
ggml-hex: HTP0 new session : session-id 0 domain-id 3
         uri file:///libggml-htp-v79.so?htp_iface_skel_handle_invoke&_modver=1.0&_dom=cdsp&_session=0
ggml-hex: HTP0 op batching: n-bufs 16 n-tensors 7168 n-ops 1024 vmem 3355443200
实际生效加速级别: NPU(Hexagon HTP)
load_tensors: offloaded 25/25 layers to GPU
load_tensors:  HTP0-REPACK model buffer size =   380.46 MiB
```

`HTP0-REPACK model buffer size = 380.46 MiB` 是"权重真的住在 NPU 上"的证据，
不是仅仅"后端注册成功了"。

## 集成过程中解决的六个真实问题

### 1. 两套 ggml 实现并存（会让后端注册互相看不见）

`libllama-jni.so` 是自包含的（llama.cpp + ggml + Vulkan 全静态链接），但
`jniLibs/` 里还留着上一版的一套预编译 `libggml-base/cpu.so`、`libllama.so`，
它们会被打进 APK，在进程里形成第二份 ggml —— 各自有独立的注册表状态。

**处理**：删掉那套遗留库；`LlamaCppNative` 的 `System.loadLibrary` 序列从五个库
简化为只加载 `libllama-jni`。

### 2. 模块缺 `ggml_backend_init` 入口

`ggml_backend_load()` 要求模块导出 `ggml_backend_init`（`ggml-backend-reg.cpp:237`），
而该入口由 `GGML_BACKEND_DL_IMPL` 只在定义了 `GGML_BACKEND_DL` 时生成
（`ggml-backend-impl.h:240-271`）。原先的 Modules 构建没定义它，模块虽然能通过
`ggml_backend_hexagon_reg()` 注册，却无法被动态加载。

**处理**：用 `-DGGML_BACKEND_DL=ON` 重新构建（见 `build-models/hex-build-dl.sh`）。

### 3. 模块的 `DT_NEEDED` 指向 App 里不存在的库

模块依赖 `libggml-base.so`，而 App 里没有这个库。Android 链接器严格按 SONAME
解析 `DT_NEEDED`，不会接受 `libllama-jni.so` 顶替，于是
`dlopen failed: library "libggml-base.so" not found`。

**处理**：把 `.dynstr` 里的 `libggml-base.so` 原地改写为 `libllama-jni.so`
（两者恰好都是 16 字节，偏移不变，ELF 仍有效）。见 `build-models/patch-needed.py`。

### 4. App 看不到 vendor 库

`libcdsprpc.so` 在 `/vendor/lib64`，Android 链接器命名空间默认不允许普通 App 看到
vendor 库。实测报 `dlopen failed: library "libcdsprpc.so" not found`。

**处理**：在 manifest 的 `<application>` 内声明（该元素**只能**放在这里，放在
`<manifest>` 下会 AAPT 报错）：

```xml
<uses-native-library android:name="libcdsprpc.so" android:required="false" />
```

该库确实在 `/vendor/etc/public.libraries.txt` 白名单内，因此声明后即可访问。

### 5. DSP skel 的搜索路径（**已解决**，见上）

### 6. 生产路径从来不加载 hexagon 模块（NPU 静默失效）

`loadAcceleratorBackends()` 原本只有**基准测试**调用，生产路径
（`QwenPhotoContentAnalyzer` → `QwenInferenceEngine` → `LlamaCppWrapper`）从不调用它。
后果：相册分析永远看不到 HTP 后端，`AUTO` 静默地退化成 CPU/GPU，
而基准却报告"NPU 可用" —— **测的路径和跑的路径不是同一条**。

**处理**：`LlamaCppWrapper.loadModel()` 内部自动调用（用 `backendsLoaded` 幂等）。

## 不再需要"NPU 默认关闭"

此前 `ModelConfig.enableHexagonNpu` 默认 false，理由是"会话创建失败会在注册表里留下
没有可用设备的 HTP 条目，导致调度器卡死、连纯 CPU 都跑不动"。

会话能建起来之后这个风险的前提消失了。现在 NPU 的启用/降级由
`AcceleratorPolicy` 决定：**默认尝试，连续失败 2 次自动降级**，并用
`npu_inference_in_flight` 跨进程标记识别"上次推理把进程搞崩了"。

## 生产路径实测（真实相册照片）

配置：`backend = AUTO`（→ NPU），`maxImageSidePx = 768`，`maxTokens = 512`，
生产提示词（含 6 字段 JSON schema），`temperature = 0.3`，
模型 **`Qwen3.5-2B-Q4_0`**（1,214,873,856 B + mmproj 668,227,264 B）。

### 回归结果（tools/verify-production.ps1，5 张跨场景真实照片）

> 下表的耗时是**稳态**值（进程已跑过至少一张）。进程刚启动后的第一张会明显更慢，
> 见下面"冷启动"一节。

| # | 分类 | 标签 | 描述 | 置信度 | 耗时 |
| --- | --- | --- | --- | --- | --- |
| 0 | `[风景, 建筑]` | `#香港 #维多利亚港 #天际线 #城市景观` | 这张照片展示了香港繁华的城市风光，前景是维多利亚港的水面和游船，背景是密集的高楼大厦和山峦。 | 0.92 | 59.0 s |
| 1 | `[风景, 建筑]` | `#香港 #维多利亚港 #天际线` | 这张照片展示了中国香港的城市风光，前景是海边的栏杆和人行道，背景是密集的高楼大厦和远处的山丘。画面中有几艘渡轮在维多利亚港的水面上航行，天空多云。 | 0.92 | 61.8 s |
| 2 | `[风景, 建筑]` | `#香港 #维多利亚港 #天际线 #城市风光` | 这张照片展示了中国香港的城市景观，前景是宽阔的水面（可能是维多利亚港），背景是密集的高楼大厦和山丘。天空多云，整体氛围宁静而壮观。 | 0.95 | 62.9 s |
| 3 | `[风景, 建筑]` | `#香港 #维多利亚港 #天际线` | 这张照片展示了香港的繁华城市景观，背景是标志性的维多利亚港和众多摩天大楼。前景中可以看到水面上的船只，整体氛围展现了这座城市的活力与现代化风貌。 | 0.92 | 62.1 s |
| 4 | `[美食, 食物]` | `#叉烧饭 #餐厅 #中式料理` | 一张俯拍视角的照片，展示了一盘盛在白瓷碗里的叉烧肉盖饭。叉烧色泽红亮油润，表面覆盖着酱汁，旁边搭配了白米饭、青菜和少许黄色腌菜作为配菜。盘子边缘印有"榕記"字样，背景为餐厅桌面环境。 | 0.95 | 65.4 s |

5 张**全部自然遇到 EOG**（159/170/170/163/197 token），零截断、零崩溃。
跨场景分类正确（风景 / 美食），**OCR 能力保留**（读出盘子上的"榕記"）。

### 已知能力边界（如实记录）

2B 与 4B 的输出**都能用**，差别在细节颗粒度：

- **4B 能认出具体地标**：国际金融中心（IFC）、中银大厦（Bank of China Tower，"金字塔造型"）。
  2B 一般只到"密集的高楼大厦"。
- **2B 出现过一次地标幻觉**：把维多利亚港写成"维多利亚港（Vancouver）"。
  值得注意的是同一张照片在 NPU 上输出正确、在 CPU 上出现了这个错误 ——
  不同后端的浮点累加顺序不同，在 temperature=0.3 下会从某一步开始分叉。
  这不是后端故障，是 2B 容量下的采样风险；4B 在同批测试中未出现。

若某个相册特别在意地标级准确度，把 4B 重新部署回来即可（见文末切换说明）。

### 性能

| 指标（2B，NPU，512px，144 图像 token） | 值 |
| --- | --- |
| **单张真实照片（稳态）** | **17–19 s** |
| **单张真实照片（进程刚启动后的第一张）** | 约 250 s（视觉塔 668 MB 首次 mmap，页缓存冷） |
| 其中视觉塔编码（CPU，无法上 NPU） | 约 5 s |
| 其中 prefill（NPU） | 约 4 s |
| 其中 decode（NPU） | 5–7 s（约 14–16 tok/s，79–101 token） |
| NPU 驻留权重 | `HTP0-REPACK` 380.46 MiB，offloaded 25/25 层 |
| 6,848 张非截图照片的批量耗时 | 约 **1.4 天**（优化前约 5 天） |

## 延迟优化：62 秒 -> 7.5 秒

单张分析的分段构成决定优化方向。四轮优化分别打视觉塔、"prefill + decode"、
分辨率、以及模型档位与 prompt：

| 阶段 | 最初 (2B/768px) | 一轮后 | 二轮后 | 三轮后 (512px) | **当前 (0.8B/512px)** |
| --- | --- | --- | --- | --- | --- |
| 视觉塔（CPU） | 31-33 s | 13-17 s | 13-17 s | 6 s | **2 s** |
| prefill（NPU） | 15-21 s | 13-14 s | ~10 s | 6 s | **1.7 s** |
| decode（NPU） | 14-16 s | 13-15 s | 6-7 s | 5-7 s | **2.4 s** |
| **单张总计** | **62 s** | 42-45 s | 32-34 s | 17-20 s | **7.5 s** |

累计 **-88%**（快了约 8 倍）。注意这是设备正常状态下的数字；设备有时会进入慢速
状态（详见"已知的间歇性变慢"），那时同一配置会慢数倍。

### 第四轮：换用 Qwen3.5-0.8B + 收紧 prompt（-56%）

**0.8B 的视觉塔只有 196 MB**（2B 是 668 MB），这是它最大的价值 —— 视觉塔编码从
6 秒降到 2 秒；LLM 侧 decode 也从 13.4 升到 22.6-25.4 tok/s。

质量代价见"Qwen3.5-0.8B 的实测记录"（约 13% 输出有明显错误）。这是需求方在
知情后选择的取舍：**速度优先**。

在 0.8B 之上又做了两项 prompt 优化（**不含**降分辨率 —— 见下）：

| 改动 | prompt 文本 | 输出 token | 效果 |
| --- | --- | --- | --- |
| 初始 | 108 | 76-110 | 8.5 s |
| schema 加约束（tags ≤4、description ≤40 字） | 126 | 70-86 | 8.0 s |
| **再精简用户指令（保留分类词汇表）** | **95** | **51-70** | **7.5 s** |

**两项都必须保留分类词汇表。** 实测把用户指令压成"识别类型、主要物体与场景"之后，
分类从 `[风景, 建筑]` 变成 `[城市, 船只]`、`[城市, 水域, 船只]` —— 模型会**自己发明
分类**。原先那句"识别照片类型（**风景、建筑、集体合影等**）"其实是在给模型一个受控的
分类集合，而分类是相册筛选与分组的基础，不能是模型即兴发挥的结果。所以最终版本保留了
枚举、只压掉与 schema 重复的部分。

### 分辨率：已经到头了（实测反直觉）

| imageSide | 图像 token | 耗时 | 生成 token |
| --- | --- | --- | --- |
| **512** | 144 | 8.0-8.9 s | 76-110 |
| 448 | 112 | 7.9-9.5 s | 97-114 |
| 384 | 84 | 7.1-7.9 s | 79-106 |

**448 并没有更快**（甚至略慢）—— 图更糊时模型反而生成更多 token 去"猜"；
**384 只省约 1 秒**，却让地名信息丢失（`#维多利亚港` 消失、冒出 `#桥梁`）。
降分辨率在这条曲线上已经饱和，因为它压缩的是视觉塔与 prefill，而 decode 是固定成本。

### 第三轮：分辨率 768 -> 512（-45%）

这是最大的单笔收益，代价是细节颗粒度。**下表是 2B 时代测的**（换成 0.8B 之后
视觉塔只剩 2 秒，分辨率的影响进一步变小，见上一节的 0.8B 对照）：

| imageSide | 图像 token | 视觉塔 | prefill | 单张耗时 | 质量 |
| --- | --- | --- | --- | --- | --- |
| 768 | 336 | 13–17 s | ~10 s | 32–47 s | 最好，从不出现地名幻觉 |
| **512（现生产默认）** | **144** | **6 s** | **6 s** | **17–20 s** | **好，多轮共 8/8 无幻觉** |
| 448 | 112 | — | — | 15–16 s | ⚠️ 1/3 编造"维多利亚港（Vau Cheung Wan）" |
| 384 | 84 | 3 s | 4 s | 14.9–17.1 s | ⚠️ 1/2 编造"维多利亚港（Vau Cam）" |

512 下的实际输出（同一批照片）：

```
分类: [风景, 建筑]
标签: [#香港, #维多利亚港, #天际线, #城市景观]
描述: 这张照片展示了香港繁华的城市天际线和维多利亚港的水景，前景中有几艘船只。
```

**再往下压是"用正确性换 2 秒"。** 384 只比 512 快 2–3 秒（视觉塔 6→3 s、prefill 6→4 s），
但幻觉率从 0/8 跳到 1/2；decode 是固定成本（5–7 s），继续砍分辨率压不动它。
所以 512 是这条曲线的拐点。

### 第一轮：视觉塔线程数与 LLM 解耦（-29%）

`mtmd_context_params.n_threads` 原本写死 4（设备有 8 核）。关键是**它和 LLM 的线程数是两回事** ——
视觉塔是独立的 mtmd 上下文、独立的 ggml 线程池：

| 配置 | 视觉塔 | 单张总计 | 结果 |
| --- | --- | --- | --- |
| 两者都 4 线程（旧默认） | 31 s | 62 s | 正常 |
| **两者都 8 线程** | — | — | **挂死**（LLM prefill 阶段，两次复现） |
| **仅视觉塔 8 线程** | **17 s** | **49–51 s** | **正常** |

所以"8 线程不可用"只对 LLM 成立。`ModelConfig.mmprojThreads` 现在默认取
`availableProcessors().coerceIn(2, 8)`，而 `numThreads` 固定 4 并加了"不要提到 8"的警告注释。

### 第二轮：精简 JSON schema（-25%）

原来的 schema 让模型生成 **6 个字段**，其中 `labels` 是纯浪费 ——
`parseAnalysisResponse` 里 labels 完全由 tags 派生
（`tags.take(5).map { VisualLabel(it.removePrefix("#"), confidence) }`），
也就是说那一整段是被要求生成、然后被丢掉的。

| 指标 | 原来 | 现在 |
| --- | --- | --- |
| prompt 的 schema 部分（`chunk[2]`） | 186 token | **108 token** |
| 实际生成 | 155–198 token | **79–101 token** |
| decode | 13–15 s | **5–7 s** |
| prefill（`n_past`） | 226 | **140–148** |

### 试过但不可行的方向（连同证据）

| 方向 | 结果 |
| --- | --- |
| **LLM 也提到 8 线程** | ❌ 挂死两次，停在 prefill 的 `find_slot` / `failed to allocate graph` 之后；第二次 `MemAvailable` 有 4.38 GB，排除内存压力 |
| **视觉塔上 NPU**（`mmprojUseGpu=true`） | ❌ 日志显示 `clip_ctx: CLIP using HTP0 backend`，随后 **SIGABRT**：`ggml_hexagon_session::flush_pending` → `ggml_abort`。Hexagon skel 的算子表里没有 conv2d/im2col，而 ViT 的 patch_embed 需要它们；后端选择 abort 而不是回落 CPU |
| **KV cache 量化**（`--ei kvQuant 1`） | ❌ **实测无收益**。它需要 Flash Attention，单开会被拒（`quantized V cache was requested, but this requires Flash Attention`）；与 `--ei flashAttn 2` 组合能跑起来，但 decode 为 6.66 / 6.52 s（13.5 tok/s），与默认的 13.3–15.9 tok/s 持平 —— NPU 的瓶颈不在 KV 带宽。而它改变了数值路径（强制开 FA），风险大于收益，故不启用 |
| **隐藏 Vulkan** | ✅ 已落地（见下） |

### 剩下的空间（诚实评估）

当前单张 17–20 秒里，视觉塔 6 s、prefill 6 s、decode 5–7 s 都已是真实计算量，
不再是某处写死的常量或浪费。继续压缩只剩这些手段，且都要付代价：

| 手段 | 预期收益 | 代价 |
| --- | --- | --- |
| 384px 分辨率 | 2–3 s | 幻觉率 0/8 → 1/2（实测） |
| 去掉 `ocr_text` 字段 | 约 1 s | 失去 OCR（界面上的"复制文字"） |
| 去掉 `confidence` 字段 | 约 0.5 s | 失去置信度 |
| 去掉采样器 penalties | 约 0.5 s | 稳定性未知 |
| 去掉 JPEG 编解码往返 | < 0.3 s | 需改 JNI 接口传裸像素，改动大收益小 |

合计最多再省 3–4 秒（到 14–16 秒），且前两项会损失功能或正确性。

**真正更大的不确定因素是设备慢速状态**：同一份二进制、同一张照片、同一配置，
实测既跑出过 17 秒，也跑出过 55–58 秒（decode 从 13.5 tok/s 掉到 2.2 tok/s）。
这个 3 倍波动的影响远大于上面任何一项微调，但它的成因尚未定位（见下）。

### 还没做但收益最大的方向：流水线并行（已实现，默认关闭）

当前是串行的：视觉塔（CPU）→ prefill+decode（NPU）→ 下一张。
而**视觉塔在 CPU、LLM 在 NPU，是两块不同的硬件**，理论上可以把第 N+1 张的
视觉塔编码与第 N 张的 LLM 推理重叠。

**已经实现并实测**（`HybridPhotoContentAnalyzer.analyzeBatch` + JNI 侧把
`mtmd_encode_chunk` 与 `mtmd_helper_decode_image_chunk` 拆成两步调用）：

| 配置（4 张，同条件） | 单张（串行） | 批量流水线稳态 |
| --- | --- | --- |
| 视觉塔 8 线程 | **17-20 s** | 26.4 s/张（比串行还慢） |
| 视觉塔 4 线程 | 21-24 s | **13.3 s/张** |

**结论：流水线要有效，必须把视觉塔线程数降到 4 左右**（`mmprojThreads + numThreads <= 核数`），
因为它与 LLM **争抢 CPU** —— 视觉塔是 CPU 密集的，而 LLM 虽然跑 NPU，其调度与数据搬运
同样要 CPU。8 线程的视觉塔把 CPU 占满，并行时两侧都变慢（实测第 1 张 decode 只有
5.77 tok/s，而独占时是 13.4）。

代价是**单张变慢约 20%**。所以对"以单张延迟为主"的场景不值得开，
默认 `ModelConfig.enableBatchPipeline = false`。

**为什么没有做成"两全"**：`mtmd_context.n_threads` 是 `mtmd_init_from_file` 时固定的，
运行中改不了；要支持"批量时降到 4、单张时用 8"需要在 vendored 的 `mtmd.h/mtmd.cpp` 里
新增一个 setter（`mtmd_set_n_threads`）。那属于给上游代码加新接口，按
`llama.cpp/AGENTS.md` 的要求没有擅自改 —— 需要的话可以加，但要接受升级 llama.cpp 时的
合并成本。

**线程安全性的依据**（读 mtmd 源码确认，不是推测）：编码路径 `mtmd_encode_chunk()` 只写
`ctx->out_embd`（随后立即 memcpy 出来），推理路径 `mtmd_helper_decode_image_chunk()`
对 ctx 的使用全是只读，且用传入的 embd 拷贝；llama context 只被推理线程使用。

### 附带修复：默认隐藏 Vulkan 后端

`LlamaCppWrapper.loadModel()` 现在会 `setenv("GGML_VK_VISIBLE_DEVICES", "")`，除非显式选
`backend = GPU_VULKAN`。两个理由任一都足够：

1. 它算错（0 层卸载正确、4 层起全乱）。
2. **它会让视觉塔崩掉**：`use_gpu=true` 时日志说 `CLIP using HTP0 backend`，但进程随后
   SIGABRT 于 `vk::Device::createComputePipeline: ErrorUnknown` —— 因为 **clip/mtmd 不遵守**
   `nativeInit` 里给主模型设的设备白名单，它们能看到全部已注册后端。

隐藏后日志变为 `Vulkan: 0 device(s); CPU: 1; HTP: 1 [HTP0]`。

### 已知的间歇性变慢（如实记录）

同一份二进制、同一张照片、同一配置，实测既跑出过 **17 秒**，也跑出过 **55–58 秒**：

| 时刻 | 配置 | 视觉塔 | prefill | decode | 总计 |
| --- | --- | --- | --- | --- | --- |
| 02:11 | 512px（生产默认） | 6 s | 6 s | 5.9 s（13.4 tok/s） | **20.5 s** |
| 02:07 | 384px | — | — | 37.9–41.7 s（**2.1–2.5 tok/s**） | 55–58 s |
| 02:12 | 384px（稍后重测） | 3 s | 4 s | 6.4–7.1 s（13.8 tok/s） | 14.9–17.1 s |

**慢的时候慢在 decode：13.5 → 2.2 tok/s（6 倍）**，而且不是内存、温度或负载问题 ——
当时 CPU 725–761% idle、温度 40.9 °C、`Thermal Status: 0`、`MemAvailable` 6.7 GB、
前台无 App、无省电模式、CPU 频率正常（2.4 GHz）。

排查过的方向：

- **不是分辨率**：同一配置在慢速状态下 384px 反而 58 s，恢复正常后同样 384px 只要 15–17 s。
- **不是反复重启的累积**（那个是另一个症状）：那种情况表现为**死锁** ——
  进程累计 CPU 时间完全不增长、卡在 `mtmd_helper_eval_chunks 开始` 之后、
  静置数分钟后自行恢复（详见下一节）。
- **本节的症状不同**：进程在**正常推进**，只是 NPU 的 decode 速度掉了 6 倍。
- **DSP 频率读不到**（`/sys/class/devfreq/` 下无对应条目），所以"NPU 降频"只是**未经证实的假设**。

**对用户的含义**：单张分析的时间会在 17 秒到 58 秒之间波动，且目前无法从应用侧控制或预测。
这也是为什么批量任务建议择机运行 —— 但要注意这与"设备忙"不完全等价：
02:07 那次设备其实是空闲的。

### 另一个症状：反复强杀进程会累积出死锁

**现象**：单张分析卡在 `mtmd_helper_eval_chunks 开始` 之后不动。判据是进程累计 CPU 时间
（`/proc/<pid>/stat` 第 14/15 字段）**在 20 秒内完全不增长** —— 线程在等，不是在算。

**排查过程**（每一步都做了对照）：

| 怀疑对象 | 对照结果 |
| --- | --- |
| 视觉塔 8 线程 | ❌ 不是：4 线程同样卡 |
| 隐藏 Vulkan（`Vulkan: 0 device(s)` 这个"有 backend 无设备"的状态） | ❌ 不是：`hideVulkan=0`（`Vulkan: 1 device(s)`）同样卡 |
| 内存压力 | ❌ 不是：`MemAvailable` 6.7–6.9 GB |
| 热降频 | ❌ 不是：40.9 °C，`Thermal Status: 0` |
| 系统有其他重负载 | ❌ 不是：CPU 761% idle，前台无 App |

**时间线锁定了成因**：00:20–00:52 之间反复运行均正常，01:01 之后每次必卡；
而故障前刚做过一连串 **`force-stop` + 重启进程**的密集测试（几十次）。
停止操作、静置 4 分钟后，**同一配置恢复正常**。

**当前判断**：这是**测试模式的产物** —— 每次强杀进程都会新建 FastRPC/DSP 会话，
而强杀不保证释放，累积到一定程度后 DSP 侧进入卡死状态，需要时间回收。
生产是**单进程长驻单例**（`QwenInferenceEngine.getInstance`），不会走到这个状态。

**未确证**：没有抓到 DSP 侧的直接证据（dmesg 无相关错误、`/dev/fastrpc-cdsp` 权限受限），
所以这是**基于对照实验与时间线的推断，不是根因定位**。
实际含义：正常批处理不受影响，但**频繁杀进程重启的调试方式不可靠**。

### ⚠️ 超时降级的文案（已修）

超时与"模型不可用"曾共用一句"基础分类结果（Qwen3.5 模型未能运行）"，
把"设备忙/变慢、稍后重试即可"说成了"模型坏了"。现在区分：

| 原因 | 输出文案 |
| --- | --- |
| 超时 | 基础分类结果（分析超时，设备可能正忙 —— 稍后重新分析即可） |
| 模型不可用 | 基础分类结果（本地模型不可用） |
| 其他失败 | 基础分类结果（Qwen3.5 模型未能运行） |

批量任务若在设备变慢期间运行，会有部分照片落到第一条 —— 可考虑在批量入口加设备空闲判断，
或让降级的照片自动重试（尚未实现）。

> ### ⚠️ 冷启动第一张明显更慢（实测 252 s）
>
> 实测原生日志（2B，NPU，768px，进程刚起来）：
>
> ```
> 12:52:22  clip_image_batch_encode: copying image 1/1 to input buffer (nx=768, ny=448)
> 12:56:05  clip_image_batch_encode: output embedding shape [2048, 336, 1]   <- 223 秒
> 12:56:17  mtmd_helper_eval_chunks 结束: 0, n_past=226
> 12:56:31  多模态生成完成: 183 tokens, 解码耗时 14158 ms (12.93 tok/s)
> 12:56:31  耗时: 252043 ms          <- 第一张
> 12:57:30  耗时:  58950 ms          <- 同一进程的第二张
> ```
>
> 慢的是**视觉塔（668 MB）首次 mmap 读入**，页缓存全冷；decode 本身一直正常
> （12.93 tok/s）。APK 重装会让页缓存彻底失效，所以最容易在"刚装完/刚重启"时看到。
>
> 这就是 `localTimeout` 取 **360 s** 而不是 300 s 的原因：252 s 对 300 s 只剩 48 s
> 余量，设备稍慢就会把首张判成超时并降级成"基础分类结果" —— 那是**把冷启动误报成
> 模型不可用**。批量任务里第一张慢几分钟可以接受，误判不可以。
>
> 注：这是**冷启动**（页缓存冷），与上面"间歇性变慢"（设备被占用）是两个不同的原因，
> 但都会落进同一条超时降级路径。

模型对照（同为 768px，自然 EOG，NPU）：

| 模型 | NPU 驻留 | 单张耗时 | decode | 质量 |
| --- | --- | --- | --- | --- |
| **2B-Q4_0（生产）** | 380 MiB（25/25 层） | **59–65 s** | 9.4–10.6 tok/s | 正确；OCR 可用；偶尔认错地标 |
| 4B-Q4_0（次选） | 951 MiB（33/33 层） | 127–165 s | 4.1–5.3 tok/s | 更细；能认出 IFC / 中银大厦 |

**选 2B 的理由是速度**：4B 的细节优势不值得 2.4 倍耗时，而相册分析是几千张的批量任务。

**回落分支实测**（原生日志）：

| 场景 | 设备选择 | 实际生效 | 单张耗时 | 输出 |
| --- | --- | --- | --- | --- |
| `reset`（NPU 优先） | `accelMode=0 -> 2 个设备` | `NPU(Hexagon HTP)` | 59–65 s | 正确 |
| `disable`（用户关闭） | `accelMode=0 -> 1 个设备` | `CPU` | 83.5 s | 正确 |
| `exhaust`（失败达阈值） | `accelMode=0 -> 1 个设备` | `CPU` | 81.9 s | 正确 |

### 切换模型档位

```bash
python tools/deploy_qwen35.py --variant 0.8b --model-dir <目录>   # 视觉塔仅 196 MB
python tools/deploy_qwen35.py --variant 2b   --model-dir <目录>   # 当前生产
python tools/deploy_qwen35.py --variant 4b   --model-dir <目录>
```

⚠️ **必须成对替换**：三个档位的 mmproj **同名不同内容**（0.8B 204,987,232 B、
2B 668,227,264 B、4B 672,423,616 B），只换主模型会留下不配套的视觉塔，而失败是静默的
（模型照跑、输出无意义）。部署脚本按字节数校验，运行时的
`QwenModel.verifyMmprojMatches` 也会再拦一道。

同时建议删掉不再使用的档位（`run-as com.example.isip rm files/models/<不用的>.gguf`）：
它既占空间，也会和当前的视觉塔形成不配套组合。

#### Qwen3.5-0.8B 的实测记录（试过，已换回 2B）

31 张真实相册照片，512px / NPU：

| 指标 | 0.8B | 2B |
| --- | --- | --- |
| 单张耗时（设备正常时） | **7.5-10.5 s** | 17-20 s |
| 视觉塔编码 | 2 s（mmproj 196 MB） | 6 s（668 MB） |
| decode | 22.6-24.0 tok/s | 13.4-15.9 tok/s |
| NPU 驻留权重 | 135 MiB | 380 MiB |
| 地名幻觉 | 有：`#港珠澳大桥`（照片是维港） | 未观察到 |
| 内容误判 | 有：烧鹅饭 -> "日式料理…梅干菜" | 未观察到 |
| 分类矛盾 | 有：动漫角色 -> `[风景, 集体合影]` | 未观察到 |
| JSON 格式偏差 | 有：tags 输出成字符串而非数组 | 未观察到 |
| **OCR** | **明显更强**：菜单、书籍封面、游戏界面 `ENERGY GEN 30`、整段问答文本 | 中等 |

**明显错误约 4/31 ≈ 13%，因此换回 2B**。但 0.8B 不是"更差"，而是**长板更长、短板更短**：
它适合以 OCR/文字为主的截图，代价是会编造具体名词（地标、菜系），而错误标签会直接
污染搜索与分类。要再用就成对部署，注意它的 mmproj 与 2B 同名不同内容。

> 顺带修掉一个由此暴露的解析缺陷：0.8B 会输出 `"tags": "#二次元#动漫#直播#视频"`
> （字符串而非数组），而旧的 `extractJsonArrayGson` 遇到非数组直接返回空 ——
> 整条 tags 被丢掉、回落到默认的 `#AI分析`，从结果上看和"模型没给标签"一样，
> 光看输出分不出是模型的错还是解析的错。现在会按 `#`、`,`、`、`、`|` 拆开。

## ⚠️ 生产路径曾经被 Vulkan 静默污染

`AUTO` 原来的回落链是 **HTP0 → Vulkan0 → CPU**。在 NPU 未启用（或会话失败降级）时
它会选中 Vulkan，而 **Vulkan 在这台 SM8750 上会算错**：产出 JSON 结构完整、
内容完全乱码的文本。

这正是此前"真实照片输出不可用"的**真正根因** —— 当时被错误归因为"2B 模型处理不了
写实照片"。现已把 Vulkan 移出自动链，`AUTO` = **NPU → CPU**。

完整证据（层数扫描、规避开关扫描、纯文本对照）见
[`gpu-vulkan-status.md`](gpu-vulkan-status.md)。

## 顺带修掉的性能回退

后端一旦"活着"，llama.cpp 会把**所有**可见加速设备纳入张量分配，与 `n_gpu_layers`
无关。实测：`ngl=0` 时 Vulkan 仍参与分配（日志出现 `Vulkan0 compute buffer size
is 531 MiB`），纯 CPU 文本推理从 **12.05 掉到 5.54 tok/s**、多模态从 13.4s 涨到 23.8s。

**处理**：JNI 按后端显式限定可用设备（`nativeAccelMode`：0=自动(NPU>CPU)、1=仅 CPU、
2=Vulkan+CPU、3=Hexagon+CPU）。修复后 `ngl=0` 只给 CPU 设备，日志变为
`CPU compute buffer size is 124.75 MiB`，文本推理回到 ~10.7 tok/s。

`ModelConfig.nativeAccelMode` 与 `llama_jni.cpp` 的 switch 一一对应。

## 当前可用状态（已在设备上验证）

| 路径 | 状态 | 实测 |
| --- | --- | --- |
| **NPU (Hexagon HTP)** | ✅ **可用，生产默认** | **0.8B：135 MiB 权重驻留、7.5 s/张**（2B：380 MiB、17-20 s/张；4B：951 MiB、127-165 s/张） |
| CPU | ✅ 可用（NPU 失败的回落目标） | 输出同样正确；未单独测 0.8B 的 CPU 耗时 |
| Vulkan | ❌ **算错且会崩视觉塔，已默认隐藏** | 见 `gpu-vulkan-status.md`；仅 `--es backend GPU` 显式放行 |

两条回落分支都已实测确认（原生日志）：`npuState=disable`（用户关闭）与
`npuState=exhaust`（连续失败达阈值）都会命中 `设备未命中（跳过，将回落到下一级）: HTP0`
→ `实际生效加速级别: CPU`，且输出依然正确。

## App 内实际走的是哪条路（真实入口验证）

前面几节测的是分析器本身。但"App 内是不是 2B + NPU"这个问题还隔着两层决策，
必须用**与 `GalleryViewModel` / `PhotoDetailViewModel` 逐字相同的对象图**验证：

```kotlin
AnalyzePhotosUseCase(
    PhotoRepository.getInstance(app),
    HybridPhotoContentAnalyzer(app),          // -> PRODUCTION_CONFIG -> AUTO -> NPU
    MobileClipProvider.getOrNull(app)         // 这一项决定 Qwen 会不会被调用
)
```

验证入口：`--ez appPath true`（见 `MultimodalTestActivity.runAppPathAnalysis`）。

### 第一层：Qwen 到底会不会被调用

`AnalyzeImageSkill` 优先跑 MobileCLIP 粗分类，**只有**满足下列之一才调用 Qwen：

- `requireDetail = true`（用户点"重新分析"）
- `clip == null`（MobileCLIP 不可用）
- CLIP 分类命中 `截图/文档/票据/证件/银行卡`
- CLIP 置信度 < 0.30

实测本机 **MobileCLIP 不可用**（`files/clip` 目录不存在 → `createOrNull` 返回 null），
所以 **每一张照片都会走 Qwen**。日志确认：

```
MobileCLIP: 不可用（clip==null → AnalyzeImageSkill 对每张图都会走精细分析）
```

> ⚠️ **这一条将来会变。** 一旦部署了 MobileCLIP（`files/clip/` 放进三个模型文件），
> 常规照片（置信度 >= 0.30 且非文档类）就**只走 CLIP，不再经过 Qwen** ——
> 那时"2B + NPU"只作用于低置信度与文档类照片。要保留全量精细分析，
> 需要调整 `AnalyzeImageSkill.DEFAULT_DETAIL_THRESHOLD` 或走 `requireDetail` 路径。

### 第二层：结果真的落库了吗

`AnalyzePhotosUseCase` → `PhotoRepository.saveAnalysisResult` → `photo_ai` 表。
注意 `saveAnalysisResult` 里有一条**静默 return**：查不到对应 photo 时只打一行 warning
就返回，不抛异常 —— 分析跑完却什么都没存下来，从调用方看不出来。所以验证必须查表。

### 验证结果

```
MobileCLIP: 不可用（clip==null → AnalyzeImageSkill 对每张图都会走精细分析）
分析前 photo_ai 行数: 0
--- [0] IMG_20260804_182632.jpg ---
耗时: 61374 ms
分类: [风景, 建筑]
标签: [#香港, #维多利亚港, #城市天际线, #摩天大楼, #行人]
描述: 一张横向的摄影照片，展示了香港繁华的城市景观。画面前景是行人在码头或
      观景平台上，中景是宽阔的海面和正在航行的船只，背景则是密集的高楼大厦和
      连绵的山脉，天空多云。
模型: qwen3.5-2b-q4_0 / gguf 置信度=0.92
分析后 photo_ai 行数: 2 （本次新增 2）
```

对应的原生日志：

```
13:14:17 [I] 模型: /data/user/0/com.example.isip/files/models/Qwen3.5-2B-Q4_0.gguf
13:14:17 [I] 设备选择: accelMode=0 -> 2 个设备
13:14:17 [I] 实际生效加速级别: NPU(Hexagon HTP)
13:14:17 [L] load_tensors:  HTP0-REPACK model buffer size =   380.46 MiB
```

数据库侧独立确认（`SELECT model_name, COUNT(*) FROM photo_ai GROUP BY 1`）：

```
qwen3.5-2b-q4_0 / gguf -> 2 张
```

**结论：App 内确实是 2B + NPU，且结果确实落库。**

> 注：验证前 `photo_ai` 是 **0 行** —— 说明此前从未在 App 里成功跑过分析
> （不是失败，是没触发过）。验证过程写入了真实照片的正确分析结果，已留在库中；
> 那些照片在后续批量分析时会被自动跳过。

> ⚠️ **跑这个验证时要保持屏幕常亮。** 它跑在 `lifecycleScope` 上，Activity 一旦离开
> RESUMED 协程就会挂起，推理停在半路 —— 表现是 `native.log` 停在某个 `decode token`、
> 进程还活着、也没有崩溃记录。实测无人值守 8 小时后就是这样停住的，
> 一度被误判成"某张照片让 NPU 卡死"。代码里已加 `FLAG_KEEP_SCREEN_ON`，
> 但手动锁屏仍会打断。

> 另一条容易被误读的记录：`dumpsys dropbox` 里与 `com.example.isip` 相关的条目
> 有上千条，但**按 `Timestamp` 过滤后**只有历史那几条（含一条
> `DefaultDispatch` 线程上的 C++ 异常 `__cxa_throw`，栈经
> `mtmd_helper_decode_image_chunk` → `llama_decode` → `ggml_backend_sched_graph_compute_async`）。
> 排查崩溃时**必须看 Timestamp**，否则会把几天前的记录当成刚才发生的。

## 复现命令

一键回归（推荐，覆盖 NPU 优先 + 两条回落分支，并**同时记录后端与输出文本**）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify-production.ps1
# 结果写到 tools/verify-production.txt
```

单次运行：

```bash
# 部署设备端启动脚本（verify-production.ps1 会自动做）
adb push tools/run_photos.sh /data/local/tmp/run_photos.sh

# 生产路径：真实相册照片，NPU 优先（count imageSide maxTokens skip npuState backend）
adb shell "sh /data/local/tmp/run_photos.sh 3 768 512 500 reset AUTO"

# 显式锁定后端做对照（现在对相册路径也生效）
adb shell "sh /data/local/tmp/run_photos.sh 1 768 512 500 reset CPU"

# NPU 策略状态机（生产降级逻辑）
#   reset=清失败计数并允许 NPU / disable=用户关闭 / exhaust=压到阈值验证自动降级
#   clear=只清"推理进行中"标记（强杀进程后复位用）

# 读取结果（该设备不把应用 logcat 交给 adb，必须以文件为准）
adb shell run-as com.example.isip cat files/bench/benchmark.txt
adb shell run-as com.example.isip cat files/bench/native.log
```

> ⚠️ **不要把中文 grep 模式传进 adb**（`adb shell "... grep '中文' ..."` 会在 sh 层
> 炸成 `no closing quote`）。用 `cat` 把日志取回主机再过滤。
> 同理，`tools/verify-production.ps1` 必须保持**纯 ASCII** —— Windows PowerShell 5.1
> 按 ANSI 读取无 BOM 的 .ps1，里面的中文会被误解码并破坏引号解析。
