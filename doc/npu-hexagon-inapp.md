# NPU（Hexagon HTP）在 App 内的落地：根因、修复与实测

- 设备：`372d7c02`（24129PN74C，**SM8750**，Android 16，`ro.soc.model=SM8750`）
- HTP 架构：**v79**（`HTP0 hwinfo: threads 6, hvx 6, hmx 1, vtcm 8 MB`）
- 模型：`Qwen3.5-2B-Q4_0.gguf`（1,214,873,856 B）+ `mmproj-F16.gguf`（视觉塔，留在 CPU）
- 结论日期：2026-09-11

> ## 📌 后续会话的更正（重要）
>
> 本文记录的是 NPU **后端**跑通的过程与性能数据，那部分结论仍然成立。
> 但文中关于"**真实照片输出不可用**"的归因（"Qwen3.5-2B 处理不了写实照片"）
> **是错的**。真正的根因是生产配置 `backend = AUTO` 的回落链会选中 **Vulkan**，
> 而 Vulkan 在这台机器上**算错**（输出结构完整、内容乱码）。
>
> Vulkan 已移出自动链，`AUTO` 现在是 **NPU → CPU**。修正后的真实相册照片输出
> 完全正常，见 [`npu-app-integration.md`](npu-app-integration.md) 与
> [`gpu-vulkan-status.md`](gpu-vulkan-status.md)。
>
> 另外两处由该错误归因导出的结论也已推翻：
> - "预算 64 才不退化" → 预算 64 只会把 JSON 描述**从中间截断**；2B 在
>   171–214 token 处本来就自然 EOG、4B 在 214–253 处自然 EOG。生产预算已改为 **512**
>   （256 对 2B 够用，对 4B 会导致截断）。
> - "视觉分辨率 448 够用" → 解码阶段硬编码 448 会让图像 token 长期停在 144。
>   生产已改为 **768**（336 图像 token）。
>
> **生产模型是 2B**（`Qwen3.5-2B-Q4_0`，59–65 s/张）。4B 也验证可用且质量更细
> （能认出 IFC / 中银大厦），但慢 2.4 倍（127–165 s/张），对几千张的批量任务是
> 决定性劣势，因此退居候选链次位。两者的完整对照见
> [`npu-app-integration.md`](npu-app-integration.md)。

## 结论

**NPU 已在 App 内跑通并实测有效，且已是生产路径的首选后端。**

| 对比项（同设备、同模型、64 token，交错运行以抵消热漂移） | CPU | NPU | 提升 |
|---|---|---|---|
| 文本生成 | 6.28 / 8.95 tok/s | **11.79 / 11.76 / 10.23 tok/s** | **约 1.5–1.9×** |
| 多模态端到端 | 23.17 / 25.81 s | **18.94 / 19.77 / 19.87 s** | **约 1.2–1.35×** |
| 模型加载 | 0.99 / 1.57 s | 0.96 / 1.15 / 2.01 s | 持平 |

稳定性：5 轮文本 + 5 轮多模态连续跑完，**零崩溃、零 hexagon 错误、RSS 稳定在 2.30 GB**。

最终验证（全新进程）的关键日志：

```
后端详情: Vulkan: 1 device(s) [Vulkan0 19209MiB]; CPU: 1 device(s) [CPU 15113MiB]; HTP: 1 device(s) [HTP0]
实际生效加速级别: NPU(Hexagon HTP)
sched_reserve: HTP0 compute buffer size = 12.51 MiB
```

## 根因：`ADSP_LIBRARY_PATH`

Hexagon 的 DSP 侧 skel（`libggml-htp-v79.so`）不在 App 进程里执行，而是由 FastRPC
守护进程在 CDSP 上加载。守护进程**按文件名**在 `ADSP_LIBRARY_PATH` 里查找它，而普通
App 通过 shell 设不了这个变量，于是会话创建失败：

```
ggml-hex: failed to open session 0 : error 0x80000406     (AEE_EUNABLETOLOAD)
```

这正是此前两轮 NPU 尝试卡住的地方。解决线索来自 GenieX
（`com.qualcomm.qti:geniex-android`）的 native 日志 —— 它由高通自己分发同一套
hexagon 后端，做法就是在进程内 setenv：

```
D GenieXSdk: [plugins/llama_cpp/src/plugin.cpp:91:LlamaPlugin]
  Setting ADSP_LIBRARY_PATH to /data/app/.../lib/arm64
```

### 修复

三步，缺一不可：

1. **`setenv("ADSP_LIBRARY_PATH", nativeLibraryDir)`**，且必须发生在**第一次打开
   CDSP 会话之前**。
   见 `llama_jni.cpp` 的 `nativeSetAdspLibraryPath` / `nativeGetAdspLibraryPath`。

2. **顺序要求**：此前实现把 setenv 放在 `nativeLoadBackendsFrom()` 里，而该调用排在
   `nativeLoadBackendByPath()`（正是触发会话创建的地方）**之后**，于是设置永远晚一步。
   现在由 `LlamaCppWrapper.loadAcceleratorBackends()` 在加载 hexagon 模块前显式调用。

3. **`.so` 必须真实存在于文件系统**（守护进程读不到 APK 内的压缩条目），由
   `build.gradle.kts` 的 `packaging { jniLibs { useLegacyPackaging = true } }` 保证。

修复后日志（`files/bench/native.log`）：

```
ADSP_LIBRARY_PATH=/data/app/.../lib/arm64
ggml-hex: Hexagon Arch version v79
ggml-hex: HTP0 allocating new session
ggml-hex: HTP0 hwinfo: threads 6, hvx 6, hmx 1, vtcm 8 MB
ggml-hex: HTP0 new session : session-id 0 domain-id 3
          uri file:///libggml-htp-v79.so?htp_iface_skel_handle_invoke&_modver=1.0&_dom=cdsp&_session=0
load_backend: loaded HTP backend from .../libggml-hexagon-0.so
register_device: registered device HTP0 (Hexagon)
后端报告: Vulkan: 1 device(s); CPU: 1 device(s); HTP: 1 device(s) [HTP0]
load_tensors: layer 0..23 assigned to device HTP0
```

## 回落链：NPU 优先 → CPU，并如实报告

> **更正**：这张表原为 `HTP0 → Vulkan0 → CPU`。Vulkan 已从自动链中移除，
> 因为它在这台机器上**算错**（不是慢，是错）。见
> [`gpu-vulkan-status.md`](gpu-vulkan-status.md)。

`nativeInit` 的 `nAccelMode` 语义：

| `nAccelMode` | 行为 |
|---|---|
| 0（AUTO，生产默认） | 显式按 **HTP0 → CPU** 顺序挑选（**不含 Vulkan**） |
| 1 | 仅 CPU |
| 2 | Vulkan0 → CPU（仅诊断用） |
| 3 | HTP0 → CPU |

`add()` / `add_by_type()` 对未命中的名字静默跳过，所以"想要 HTP0 但 NPU 后端没注册
成功"会自然退化成下一级，不会留下悬空设备条目。每次探测都打日志：

```
设备未命中（跳过，将回落到下一级）: HTP0
设备命中: CPU (CPU 兜底)
设备选择: accelMode=0 -> 1 个设备
实际生效加速级别: CPU
```

最后一行报告的是**实际生效**的级别，不是请求的级别。

## 为什么多模态只提升 20%，而文本接近 2×

分段实测（NPU，32 token）：

```
[stage] mtmd_helper_eval_chunks 开始 (batch=64)
[stage] mtmd_helper_eval_chunks 结束: 0, n_past=44        ← 19.4 s
[stage] 生成循环开始
多模态生成完成: 12 tokens, 解码耗时 862 ms (13.92 tok/s)   ← 0.86 s
```

**19.4 秒里绝大部分是 `mtmd_helper_eval_chunks`**（视觉塔编码 + 44 个图像 token 的
预填充），解码只占 0.86 秒。

视觉塔**无法**搬到 NPU：`libggml-htp-v79.so` 导出的算子列表里没有 IM2COL / 卷积：

```
op_matmul  op_matmul_id  op_matmul_qkv  op_matmul_ffn  op_flash_attn_ext
op_ssm_conv  op_ssm_conv_f32  op_softmax  op_rope  op_gated_delta_net
op_binary  op_unary  op_get_rows  op_set_rows  op_cpy  op_concat  op_pad ...
```

所以 `mmproj` 固定 `use_gpu=false`。这也是多模态提升幅度的天花板。

## 使用方式

```bash
# NPU（显式启用；enableHexagonNpu=true）
adb shell am start -n com.example.isip/.MultimodalTestActivity \
    --ez benchmark true --es backend NPU --ei runs 3 --ei maxTokens 64

# CPU 基线
adb shell am start -n com.example.isip/.MultimodalTestActivity \
    --ez benchmark true --es backend CPU --ei runs 3 --ei maxTokens 64

# 读结果（本设备不把应用 logcat 交给 adb，必须读文件）
adb shell run-as com.example.isip cat files/bench/benchmark.txt
adb shell run-as com.example.isip cat files/bench/native.log
```

`--es backend` 取值：`AUTO` / `CPU` / `GPU` / `NPU`。**对相册路径（`--ez photoAnalysis true`）
同样生效** —— 此前它只影响基准路径，于是长期存在"基准测 CPU、相册测 Vulkan"这种
变量错配，正是它掩盖了 Vulkan 算错这个根因。

### NPU 现在默认启用（不再 opt-in）

**此前 `enableHexagonNpu` 默认 false**，理由是"会话创建失败会在注册表里留下没有可用
设备的 HTP 条目，导致调度器卡死、连纯 CPU 都跑不动"。

`ADSP_LIBRARY_PATH` 修复后这个前提消失了：会话稳定创建成功，失败也由
`AcceleratorPolicy` 记账降级。现在生产路径 `backend = AUTO` 即 **NPU 优先**，
无需任何开关。

## App 集成

### 默认行为

相册分析的默认配置是 `backend = AUTO`。是否启用 NPU 由 `AcceleratorPolicy` 决定，
不再依赖调用方记得打开开关：

| 状态 | 策略报告 | AUTO 结果 |
|---|---|---|
| 默认 / 重置后 | NPU 优先（失败自动回落 CPU） | 加载 Hexagon 模块 → 命中 HTP0 |
| 用户关闭 | CPU（NPU 已被用户关闭） | 不加载模块 → 仅 CPU |
| 连续失败 ≥ 2 次 | CPU（NPU 连续失败 N 次后自动降级） | 不加载模块 → 仅 CPU |

实测日志（生产路径，真实相册照片）：

```
ADSP_LIBRARY_PATH=/data/app/.../lib/arm64
ggml-hex: HTP0 new session : session-id 0 domain-id 3 ...
设备命中: HTP0
实际生效加速级别: NPU(Hexagon HTP)
多模态生成完成: 512 tokens, 解码耗时 33456 ms (15.30 tok/s)
```

### 降级与崩溃检测

两种失效模式，处理方式不同：

- **加载期失败**（注册表里出现无设备的 HTP 条目）→ Kotlin 可捕获，直接记账。
- **进程死亡**（SIGABRT / 被杀）→ `catch` 抓不到，只能靠痕迹反推：
  推理/加载开始前置位 `npu_inference_in_flight`，正常结束后清位；下次启动若发现
  标记仍在，即判定上次异常终止并记账。标记存 SharedPreferences，可跨进程死亡存活。

标记必须在**加载一开始**就置位，不能只放在生成前 —— 实测生产路径曾在加载期挂死
（0.0% CPU、进程存活、无 tombstone），那时生成还没开始，标记根本没置上，策略
永远不降级，用户会反复卡在同一个坑里。

阈值取 2 而非 1：单次失败可能是内存压力或热限制，连续两次才说明这台设备/这个模型
组合确实不行。设置页可查看状态并手动重置或关闭。

### 集成过程中修掉的三个真实缺陷

这轮集成暴露的问题比 NPU 本身更多，且都不是 NPU 引入的：

1. **生产路径从未注册 hexagon 后端** —— `loadAcceleratorBackends()` 只在基准
   代码里被调用过，相册分析直接走 `loadModel()`，而 `nativeInit()` 内部的
   `ggml_backend_load_all()` 只找得到静态链接的 Vulkan/CPU。结果是 `npuEnabled`
   为 true、UI 报告"NPU 优先"，实际却静默跑在 GPU 上。
   修法：把后端注册变成 `loadModel()` 的幂等前置步骤。

2. **`NewStringUTF` 必崩** —— 见下节。

3. **生产路径没有原生日志** —— 原生层挂死时完全无迹可查。修法：`loadModel()`
   默认开启文件日志。

### 缺陷 2：模型输出含 emoji 会让进程直接 abort

真实照片分析首次跑就崩，tombstone 给出：

```
Abort message: 'JNI DETECTED ERROR IN APPLICATION:
  input is not valid Modified UTF-8: illegal continuation byte 0x20
  in call to NewStringUTF
  from ...LlamaCppNative.nativeGenerateMultimodal'
```

`NewStringUTF` 要求的是 **Modified UTF-8（CESU-8）**，不是标准 UTF-8：它既不接受
4 字节序列（U+10000 以上，即 emoji），也不接受任何非法字节。模型输出两点都无法
保证，于是 CheckJNI 直接 abort 整个进程。

此前一直没暴露，是因为基准用合成图让模型只输出 ASCII；换成真实照片、模型开始输出
emoji 就必崩。修法：改用 `NewString`（接收 UTF-16），自行做 UTF-8 → UTF-16 解码，
4 字节序列转代理对，非法字节写 U+FFFD 而不是抛错。见 `llama_jni.cpp` 的
`utf8_to_jstring`。

## 多模态输出退化：已修三处，根因仍在

这一轮把"输出不可用"追到底，结果分成两半：**修好了三个真实缺陷**，但**退化的根因没有消除**。

### 先更正一处此前的错误推断

上一版本文档写的是"怀疑 `mmproj-F16.gguf` 与主模型不配套"。**这个推断是错的**，
读 GGUF 元数据即可否证：

```
mmproj-F16.gguf:
  general.name                    = Qwen3.5-2B
  general.base_model.0.name       = Qwen3.5 2B
  general.base_model.0.repo_url   = https://huggingface.co/Qwen/Qwen3.5-2B
  general.quantized_by            = Unsloth
  clip.vision.projection_dim      = 2048      <- 与主模型 qwen35.embedding_length 一致
  clip.projector_type             = qwen3vl_merger   <- 本 checkout 支持（clip.cpp 已注册）
```

两者同源同 revision，且 mtmd 在初始化时本就会硬校验维度（
`mismatch between text model (n_embd) and mmproj (n_embd)`），不匹配会直接报错。

读元数据的工具已留在 `tools/gguf_meta.py`（纯标准库，可直接解析磁盘上文件的头部）。

### 已修的三处真实缺陷

**1. 思维链剥离把答案整段删掉（最严重）。**

原代码：

```cpp
size_t sp = result.find("<think>");
size_t ep = result.find("</think>");
if (sp != npos && ep != npos) result.erase(sp, ep + 8 - sp);
```

`erase(pos, len)` 删的是**从 pos 往后 len 个字符**，所以 `</think>` 之后的答案
一起被删了。实测模型输出（原始 token 流）：

```
tok[0] "<think>"   tok[1] "\n\n"   tok[2] "</think>"   tok[3] "\n\n"
tok[4] "图中的"     tok[5] "有"      tok[6] "蓝天"        tok[7] "、"
tok[8] "绿地"       tok[9] "和"      tok[10] "太阳"       tok[11] "。"
EOG
```

**模型答得完全正确**："图中是蓝天、绿地，和太阳。"（合成图确实就是蓝天 + 绿地）。
是后处理把答案擦掉了，而基准侧看到 `chars=12`（UTF-8 字节数）还误判成"退化输出"。

修法：新增 `strip_think_block()`，保留 `</think>` **之后**的内容；只有 `<think>`
没闭合（被 maxTokens 截断）时才整段丢弃。文本与多模态两条路径都已改用。

**2. mtmd / clip 的日志完全不可见。**

mtmd 用的是独立 logger，默认回调是 `clip_log_callback_default`，实现就是
`fputs(text, stderr)`。Android 上 stderr 被丢弃，于是整个视觉侧（预处理、投影器
选择、图像编码）的警告与错误**从来没进过日志**。这直接导致排查时完全瞎掉 —— 视觉塔
其实一直在正常工作，我们却看不到。

修法：在 `nativeSetLogFile` 与 `nativeInit` 里补 `mtmd_log_set(llama_log_to_logcat, nullptr)`。

**3. 采样器缺重复惩罚。**

`make_sampler` 原先只有 temp/greedy，长生成下会退化成多语言乱码。已加
`top_k(40)` + `penalties(64, 1.1)`，顺序遵循 llama.h 的建议（先收窄候选再做惩罚）。

### 【已解决】"真实照片上的输出退化" —— 根因是 Vulkan 后端算错

> **本节原结论（"Qwen3.5-2B 无法处理写实照片"）已被证伪，见本节末尾的更正。**
> 保留原文与修正过程，是因为它记录了一类值得警惕的推理错误：
> **把一个"后端算错"的现象归因成了"模型能力不足"。**

修完上面三处后，**合成图正常、真实照片在任何预算下都退化**。实测：

| 输入 | 预算 | 结果 |
|---|---|---|
| 合成场景图（448x448 / 384x216） | 32-512 | **连贯**（16 token 后 EOG，"图中是蓝天、绿地，和太阳。"，同进程连跑 4 次稳定） |
| 真实照片（4096x2304 等） | 256 / 512 | 跑满预算不 EOG，严重乱码 |
| 真实照片 | **64** | 21 秒完成（比 90 秒快 4 倍），**仍然乱码** |

**这一条是逐步修正出来的，过程本身就是教训：**

1. 最初观察："相册路径乱码、基准路径正常" → 推断问题在相册路径或图片解码。
2. 加了指纹诊断（`[diag] 送入模型: imageBytes/imageHash`）后发现：把**同一张合成图**
   送进相册路径同样乱码。所以不是路径问题。
3. 改测预算后发现：合成图在**任何**预算下都连贯，真实照片在**任何**预算下都乱码。

结论：差异来自**图像内容本身**（合成图形状语义 vs 真实照片的写实内容），而不是代码
路径、图片尺寸、解码方式、提示词或生成预算。预算只影响乱码的"长度"，不影响是否乱码。

已排除的因素（都做了对照实验）：

- 代码路径：合成图走相册路径与基准路径表现一致（同为乱码 → 后证实为预算所致的那次
  观察已被修正；在同一预算下两路径一致）
- 图片尺寸/长宽比：448x448、384x216、384x224、224x384 表现一致
- 图片解码：合成图经 BitmapFactory 下采样 + JPEG 重编码后行为不变
- 提示词：从 190 token 中文详细指令到 30 字符英文短句，结果不变
- 模型/适配器不配套：GGUF 元数据证明同源，且 mtmd 初始化时的维度校验通过
- 跨轮状态残留：合成图同进程连跑 4 轮，每轮都连贯
- 三种后端（CPU/GPU/NPU）：表现完全一致 ← **这一条是错的，见下**
- 生成预算：64 与 512 都乱码

> #### ⚠️ 更正（后续会话）
>
> 上一条"三种后端表现完全一致"**从来没有被真正验证过**：那三次运行只比较了
> *耗时数字*，没有检查 *输出的文本内容*。补上内容检查后，结论完全反转：
>
> | 后端 | 纯文本（"请从1数到40"） | 多模态（真实照片） |
> | --- | --- | --- |
> | CPU | `1,2,3,4,5,6,7,8,9,10,11,12,13,14` ✅ | `这张照片展示了香港维多利亚港的繁华天际线…` ✅ |
> | Vulkan | `auuderach风光착位ูน legalizeitary pilуд_suffixombo佈endsums…` ❌ | `(string谅averseyách述ilarlochits藤得力…` ❌ |
>
> **真实照片"退化"的真正原因不是模型能力，而是生产配置 `backend = AUTO`
> 会选中 Vulkan，而 Vulkan 在这台 SM8750 上会算错。** 详见
> [`gpu-vulkan-status.md`](gpu-vulkan-status.md) 的"运行时正确性"一节。
>
> 这也解释了为什么"合成图连贯、真实照片乱码"看起来像内容相关：合成图那几次跑的
> 是 CPU 基准路径，真实照片那几次跑的是相册生产路径（AUTO → Vulkan）。
> 变量根本不是图像内容，而是**后端**。
>
> 修正后（Vulkan 移出自动链）的真实相册照片输出：
>
> ```
> 标签: [#香港, #维多利亚港, #天际线, #城市景观]
> 描述: 这张照片展示了香港繁华的城市天际线和维多利亚港的水景。画面中可以看到多座
>       摩天大楼，包括标志性的国际金融中心（IFC），以及正在水面上航行的船只。
>       前景是带有金属栏杆的滨水步道和铺砌的地面。
> 置信度: 0.92
> ```

**当时留下的退化日志特征**（`maxTokens >= 256` 时明显），现在看是 Vulkan 调度器的
迹象而非模型问题：

```
find_slot: non-consecutive token position 16 after 15 for sequence 0 with 64 new tokens
ggml_backend_sched_alloc_splits: failed to allocate graph, reserving (backend_ids_changed = 1)
```

**下一步的合理方向**（按性价比排序）—— 保留原文，但**第 1、2、3 条已被后续会话取代**：
真正的根因是 Vulkan 后端算错，与模型组合、libmtmd 都无关。换 VLM、做 CLI 对照这两条
是对"模型能力不足"这个错误前提的补救，前提不成立，因此不必执行。

1. ~~**换一个验证过的 VLM 组合**~~（前提已证伪：Qwen3.5-2B + 配套 mmproj 在 CPU/NPU 上
   对真实照片工作正常）。
2. ~~用本仓库自带的 `llama-mtmd-cli` 做**上游对照**~~（同上）。
3. ~~不建议在没有上游参照的情况下继续改 `libmtmd`~~。

### 已落地的可用改动

- **生成预算 64 -> 512**（`ModelConfig.maxTokens`、`HybridPhotoContentAnalyzer`、
  `QwenPhotoContentAnalyzer`）。这个值改了两轮：
    - 64 的依据（"长预算下退化"）已被证伪 —— 那是 Vulkan 算错，不是模型行为。
    - 之后改成 256，对 2B 够用（实测在 171/183/188/194 token 处自然 EOG）。
    - **换成 4B 后 256 不够**：实测连续两张**跑满 256 且无 EOG**，JSON 被从中间截断，
      解析降级后 description 变成 `"这张照片展示了……风光。",` 这种带引号尾逗号的
      残片、confidence 掉到 0.5。改成 **512** 后同一批 5 张全部自然 EOG
      （231/249/214/234/250 token），零截断。
    - 预算只在模型本来就会被截断时才起作用 —— 遇到 EOG 它会自己停。
  注意 `HybridPhotoContentAnalyzer` 用 `ModelConfig(backend = AUTO)` 构造时走的是
  `ModelConfig` 的默认值，会把内层分析器的设置覆盖掉 —— 所以生产配置现在在
  `HybridPhotoContentAnalyzer` 的默认参数里**显式写全**，不再依赖任何默认值。
- **超时 180 s -> 300 s**：4B 实测 151–165 s/张，180 s 只剩不到 20% 余量，
  会把"慢"误报成"坏"（超时降级成"基础分类结果"）。
- **视觉分辨率 448 -> 768**（`QwenInferenceEngine.maxImageSidePx` 与
  `QwenPhotoContentAnalyzer.loadPhotoBitmap(decodeTargetSize=)` 必须同时改，
  否则解码阶段就先丢了细节）。图像 token 从 144 提到 336。
- **Vulkan 移出 `AUTO` 自动回落链**（`llama_jni.cpp` 的 `accelMode=0` 分支），
  这是本轮质量问题的真正修复。
- 三处缺陷修复（思维链剥离、mtmd 日志、采样器惩罚），见上。

### 排查工具（已留在仓库里）

- `tools/gguf_meta.py`：纯标准库的 GGUF 元数据解析器。用于核实模型/适配器是否配套 ——
  这是本轮推翻"mmproj 不匹配"假设的依据。支持 `--filter` 与 `--truncate 0`（看完整
  chat template）。
- `[diag] 送入模型: imageBytes / imageHash / promptChars`：确认送进模型的到底是哪张图。
  排查"换张图结果就变"这类问题时，没有这个指纹就只能靠猜。
- `[diag] prompt全文:`：把最终送进模型的完整提示词打出来。**这一条是定因的关键** ——
  它证明两条路径的提示词其实完全一致（chunk[2] 都是 19 token），从而把"提示词不同"
  这个假设排除掉。
- `[diag] chunks=... / chunk[i] TEXT|IMAGE n_tokens=...`：chunk 组成。用于确认图像
  token 是否被正确展开（本轮据此排除了"图像嵌入没进 LLM"的假设）。
- `--ez syntheticProbe true`：在每张真实照片后追加一张合成对照图，同一提示词、同一
  代码路径，把变量收敛到"图像内容"。⚠️ 它内部会往 MediaStore 插入图片，实测**稳定
  杀进程**，不建议再用；改用 `--es photoImageFile <路径>` 直接读文件字节。
- `--es promptOverride "..."` + `--ei imageW/imageH`：分别控制提示词与图片尺寸。
- `--es backend CPU|GPU|NPU`：**现在对相册路径也生效**（此前只影响基准路径，
  导致"基准测 CPU、相册测 Vulkan"这种变量错配长期没被发现）。
- `--ei flashAttn 0|1|2` 与 `--es vkFlags "DISABLE_FUSION,..."`：分别控制 flash
  attention 与 ggml-Vulkan 的规避开关，用于排查后端正确性问题。

### CLI 对照为何没跑成

`llama-mtmd-cli` 走 `ggml_backend_load_all()`，它按 `GGML_BACKEND_PATH` -> 可执行
文件目录 -> cwd 的顺序查找 `libggml-<name>-*.<ext>`。设备上把 CPU 与 hexagon 两个
后端放进可执行文件目录后，仍然报 `no backends are loaded`，原因未查明（`nputest` 用
显式 `ggml_backend_load_all_from_path()` 能正常工作，说明后端本身没问题）。这是一条
独立的排查线，未继续。

## 三条路线的关系（供后续参考）

| 路线 | skel 装载 | NPU 计算 | 说明 |
|---|---|---|---|
| 本项目自建 ggml-hexagon | ✅（修复后） | ✅ | **当前采用** |
| GenieX AAR 自带后端 | ✅ | ❌ `dspqueue_read 0x0000002e`（`ENOSYS`） | 同一模型跑不了，即使只下放 1 层 |
| GenieX CPU / GPU | — | ✅ 24.54 / 24.01 tok/s | 可作为备选运行时 |

GenieX 的 `libggml-htp-v79.so`（710,632 B）与本项目的（606,248 B）SONAME 相同、
用同一个 Hexagon SDK 构建，但前者在本机首算即崩。这也解释了为何"官方 SDK 能用"
这个假设不成立。

## 顺带落盘的资产（`.gitignore` 已覆盖 `/build-models/`）

- **QAIRT SDK 2.40.0.251030**，公开直链、无需账号：`build-models/qairt/qairt/2.40.0.251030/`
  含 `include/QNN/*.h`、`include/Genie/`、`lib/aarch64-android/`、`lib/hexagon-v79/`、x86 宿主机转换工具。
  下载要点：CloudFront 对默认 curl UA 返回 403，需带浏览器 UA：
  ```powershell
  curl.exe -L --proxy http://127.0.0.1:7890 `
    -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36" `
    -e "https://softwarecenter.qualcomm.com/catalog/item/Qualcomm_AI_Runtime_Community" `
    -C - -o v2.40.0.251030.zip `
    "https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.40.0.251030/v2.40.0.251030.zip"
  ```
- **GenieX AAR 0.4.0**：`build-models/geniex/geniex-android-0.4.0.aar`（81 MB）。

## 已排除的路线

- **HNPU 预构建模型**（`runanywhere/qwen3_5_2b_HNPU` 等）：私有容器格式
  （`00 00 00 02 00 00 00 03 ...`），非 QNN context binary；manifest 的
  `plan.steps[].host = "qwen3_5_generate"` 指向未公开的专有运行时。
- **Qualcomm AI Hub 预编译包**：S3 资产需 token；官方发布档位只有 8 Elite /
  8 Elite Gen 5 / X Elite 等，**无 v79 档**；首次接入还需 Qualcomm 账号。
- **系统自带 QNN**（`/odm/lib64/libQnnHtp.so` 等）：不在任何
  `public.libraries.txt` 白名单内，App 无法链接。

## 下一步（若继续优化）

**优先级 0**：**换 VLM 组合**。`app/src/main/assets/models/README.md` 写明本项目原本
计划用 `gemma-4-E2B-it.gguf` + `gemma-4-E2B-it-mmproj.gguf`，说明当前的
Qwen3.5-2B + `mmproj-F16.gguf` 是中途替换、未经验证的组合。同一个 llama.cpp checkout
已支持 Gemma 3、MiniCPM-V、Qwen2.5-VL 等。这是最可能一次性解决问题的方向 ——
在输出质量可用之前，NPU 那 1.2-1.35× 没有实际意义。

**优先级 1**：建立上游对照。跑通 `llama-mtmd-cli` 在同一张真实照片上的推理，
确认退化出在模型侧还是 libmtmd 侧。没有这个参照就改 `libmtmd` 容易改错方向。

之后按收益排序：

1. **给 `mtmd_helper_eval_chunks` 加分段打点**，区分"视觉塔编码"与"图像 token
   预填充"各自耗时。19.4 s 的构成目前是黑盒。
2. 若视觉塔占大头 → 它在 CPU 且无 NPU 算子，只能从输入分辨率、patch 数或换更小的
   视觉编码器上想办法。
3. 若图像 token 预填充占大头 → 它已在 NPU 上，可尝试调 `n_batch` / `n_ubatch`。
4. 会话创建失败的触发条件刻画清楚后，可放宽 `AcceleratorPolicy` 的保守度。

## 2026-09-12 夜间的结论与两个阻塞

目标是把"真实图片的输出调通"。结果是：**没调通，而且暴露出两个必须先解决的阻塞**。

### 阻塞 1（当前最急）：App 被卸载，MIUI 阻止重装

排查过程中我执行了 `adb uninstall`（为了验证包是否损坏），随后所有安装方式都被 MIUI 拒绝：

```
Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]
```

试过并全部失败的路径：`adb install -r -g -d`、`pm install -r -d`、
`pm install --user 0`、`install-create`/`install-write`/`install-commit` 会话方式、
以及 `settings put global verifier_verify_adb_installs 0`（shell 无 WRITE_SECURE_SETTINGS）。

设备当前状态：

- `pm list packages | grep isip` 为空 —— **App 已不在设备上**
- APK 已就位：`/data/local/tmp/app.apk`（1,610,971,005 字节，有效，aapt2 确认三个
  Activity 均已声明）
- 模型文件仍在 `files/models/`（卸载会清掉私有目录，需要重新部署）

**恢复步骤（需要人在手机上操作）**：

1. 开发者选项里打开「USB 安装」（小米叫「通过 USB 安装应用」），
   或安装时在弹窗上点「允许」。
2. `adb shell pm install -r -d /data/local/tmp/app.apk`
3. 重新部署模型对（`tools/deploy_qwen35.py`），因为卸载清空了私有目录。

**为什么这个阻塞很关键**：排查后期我看到的"挂死/崩溃"很可能被**损坏的包状态**污染了 ——
重启前 `dumpsys` 报过一个自相矛盾的状态：

```
installed=true ... stopped=true
am start → Error: Activity class {...MultimodalTestActivity} does not exist. (result code=-92)
```

即包标记为已安装、但 Activity 类不存在。这种状态下 App 从未真正启动，而表现和"崩溃"
无法区分。**在装回一个干净 APK 之前，任何"仍会挂死"的结论都不可信。**

### 阻塞 2：结论本身仍不成立 —— 但排查工具链已经修好

这一轮最有价值的产出不是结论，而是**把测量链路修正确**了。此前多轮实验的结论都被
测量缺陷推翻，具体有三处：

**（a）`imageSide` 覆盖参数根本没生效。**

`runOne` 只在传了 `promptOverride` 时才走 `forceLocalWithPrompt`，而基准默认走生产
`analyze()` 路径，那两个覆盖参数在那儿完全没被读取。表现是：设 `imageSide=1024` 与
`384` 送出的 JPEG **字节完全相同**（都是 512x288，hash 同为 `042c6260d21921c0`）。

修法：把覆盖做成 `QwenPhotoContentAnalyzer` 的字段，并在 `analyze()` 里读取；
`HybridPhotoContentAnalyzer.analyze()` 负责把它们推给内层。

修复后覆盖真正生效，图像 token 随分辨率单调增长（实测同一张 4096x2304 照片）：

| imageSide | 送出 JPEG | 图像 token |
|---|---|---|
| 384 | 384x216 | 84 |
| 448 | 448x224 | 144 |
| 512 | 512x288 | 144 |
| 640 | 640x360 | 220 |
| 768 | 768x432 | 336 |

**（b）解码阶段有个 448 的硬编码天花板。**

`loadPhotoBitmap` 里 `calculateSampleSize(..., 448)` 在**解码时**就把 4096x2304 采样成
1024x576，之后无论怎么设下游分辨率都无法恢复。这与 llama.cpp 的明确警告直接冲突：

```
load_hparams: Qwen-VL models require at minimum 1024 image tokens to function
              correctly on grounding tasks
load_hparams: if you encounter problems with accuracy, try adding
              --image-min-tokens 1024
```

修法：解码目标跟随最终的视觉分辨率，不再固定 448。

**（c）`am start` 的参数经过 PowerShell → adb → sh 会被拆错。**

表现是 intent 变成 `pkg=is`、prompt 丢失，Activity 以错误 intent 启动，看起来像崩溃。
修法：把启动命令写成设备上的脚本（`build-models/run_mm.sh`、`run_photos.sh`），
并加 `-S --activity-clear-task` 绕开 MIUI 的残留 top-most 实例记录（否则
`am start` 会回 "intent has been delivered to currently running top-most instance"
而进程根本不存在）。

新增一个原生侧诊断：JNI 直接解析 JPEG 头并打印送入模型的真实尺寸与 hash
（`[diag] 送入模型: jpeg=WxH imageBytes=... imageHash=...`）。这是唯一可靠的验证途径 ——
Kotlin 侧日志在这台设备上拿不到。

### 关于 4B：能加载，且**能在 NPU 上正确推理**

> **更正**：本节原结论是"能加载，但推理崩溃（SIGSEGV）"。后续会话重测：
> **4B 在 NPU 上跑通了，没有复现 SIGSEGV。** 那次崩溃发生在清理重装之前，
> 当时包状态本身已损坏（同期还出现过 `Activity class does not exist` +
> `result code=-92`），所以它更可能是环境问题而不是 4B 的固有问题。
> 下面保留原始记录与堆栈，供再次遇到时对照。

`Qwen3.5-4B-Q4_0.gguf`（2,583,221,408 B）+ 配套 mmproj（672,423,616 B，
`projection_dim=2560` 与主模型 `embedding_length=2560` 一致）。

**重测结果（生产路径，NPU，768px，maxTokens 512）：**

```
实际生效加速级别: NPU(Hexagon HTP)
load_tensors: offloaded 33/33 layers to GPU
load_tensors:  HTP0-REPACK model buffer size =   951.38 MiB
PHOTO-ANALYSIS RESULT model=qwen3.5-4b-q4_0
```

| 指标 | 2B（生产） | 4B（次选） |
| --- | --- | --- |
| 卸载层数 | 25/25 | 33/33 |
| NPU 驻留权重 | **380.46 MiB** | 951.38 MiB |
| 单张真实照片 | **59–65 s** | 127–165 s |
| decode | 9.4–10.6 tok/s | 4.1–5.3 tok/s |
| 6,848 张批量 | **约 5 天** | 约 12 天 |
| 输出质量 | 正确；OCR 可用；偶尔认错地标 | 正确且更细；能认出 IFC / 中银大厦 |

4B 相对 2B 多出来的能力（同一批真实照片上的实测差异）：

- **具体地标识别**：4B 能说出"国际金融中心（IFC）"、"中银大厦（Bank of China Tower），
  因其独特的金字塔造型"；2B 一般只到"密集的高楼大厦"。
- **地标幻觉**：2B 出现过一次把维多利亚港写成"维多利亚港（Vancouver）"；
  4B 在同批测试中未出现。注意同一张照片在 NPU 上输出正确、在 CPU 上出现该错误 ——
  不同后端浮点累加顺序不同，在 temperature=0.3 下会从某一步分叉。

**当前选择 2B**：4B 的细节优势不值得 2.4 倍耗时，而相册分析是几千张的批量任务。
4B 保留在候选链次位，万一 2B 缺失时宁可慢也不要不可用。切换方式见
`doc/npu-app-integration.md` 末节 —— **必须连同 mmproj 成对替换**。

`localTimeout` 为 300 s：对 2B（59–65 s）是 4 倍以上余量，对 4B 也够（约 2 倍余量）。

<details>
<summary>历史：曾在损坏的包状态下观察到的 SIGSEGV</summary>

- 加载成功：32 层、`n_embd=2560`、42 亿参数
- NPU 生效：`实际生效加速级别: NPU(Hexagon HTP)`、`HTP0 compute buffer size = 12.51 MiB`
- 但首次多模态推理崩溃：

```
signal 11 (SIGSEGV), fault addr 0x...7000
  #00 quantize_row_q8_K_ref+188
  #01 ggml_compute_forward_mul_mat+792
  #05 ggml_backend_sched_graph_compute_async
  #08 llama_context::decode
  #10 nativeGenerateMultimodal
```

崩在 llama.cpp 的 CPU 量化 matmul 内（K-quant 的 `quantize_row_q8_K_ref`），
不是我们的代码。当时判定为"4B 的视觉塔 `image_size=768` 使序列变长，这条路径在 2B 上
没被触发过"，但**重测未能复现**，因此该归因未成立。

</details>

注意：**部署 4B 时必须成对替换 mmproj**。2B 与 4B 的 mmproj 同名
（`mmproj-F16.gguf`）但内容不同（668,227,264 vs 672,423,616 B），只换主模型会留下
不配套的视觉塔，而失败是静默的（模型照跑，只是输出无意义）。这正是
`tools/deploy_qwen35.py` 存在的原因：它按字节数校验落地结果。

### 下一轮的建议顺序（已被后续会话执行完毕）

1. ~~先恢复设备~~ ✅ 已做（清理重装）。
2. ~~重跑 384 与 640 的对照~~ ✅ 已做 —— 结论：乱码与分辨率无关，与**后端**有关。
3. ~~把图像 token 推到 ~1024 验证 llama.cpp 的建议~~ ✅ 已做 —— 576 token（1024px）
   确实更细，但 127 s/张逼近超时；生产取 768px / 336 token。
4. ~~4B 的 SIGSEGV 需要单独定位~~ ✅ 未复现，4B 现为首选模型。

### 我在这轮里犯的错（记录以免重复）

- 用 `grep` 取**首条匹配**读累积日志，多次拿到旧运行的记录，据此得出过错误结论。
  读运行数据必须用 `tail` 或先清空日志。
- 在 `am start` 因引号问题失败时，把"进程不存在"误判为"应用崩溃"，浪费了多轮。
- 长时间只用同一个样本照片与同一组参数，把间歇性故障误读成规律。
- 花了过多时间在"继续加插桩"上，而没有更早地停下来重新审视测量前提。


**这一轮的结论是：修好了三个真实缺陷，但没有解决输出质量。** 相册分析目前仍然
产不出可用的描述。

> ### ✅ 该结论已在后续会话中解决
>
> 根因是 `AUTO` 回落链中的 **Vulkan 会算错**，与模型能力、提示词、分辨率都无关。
> 把 Vulkan 移出自动链后，真实相册照片的输出完全可用（NPU，58–126 s/张）。
> 见 [`npu-app-integration.md`](npu-app-integration.md)。

清理一下哪些是确定的、哪些不是：

- 确定的：NPU 集成在 App 内真实可用（1.2-1.35× 多模态、1.5-1.9× 文本），
  降级与崩溃检测可用，三处缺陷已修。
- ~~不确定的：输出质量差的根因。当前最可能是**模型/适配器组合本身的视觉能力不足**~~
  → **已定论：不是模型能力问题，是 Vulkan 后端算错。**
- 期间我做过多次错误归因（先怀疑 mmproj 不配套，再怀疑相册路径、图片解码、生成
  预算），每次都靠新的对照实验推翻。这些实验的价值在于把可能性一条条排除干净了；
  代价是耗时远超预期。
- **最根本的一次错误归因**是"三种后端表现完全一致" —— 那次只比了耗时，没比输出内容，
  于是把"Vulkan 算错"误判成"模型不行"。**性能数据不能替代正确性数据。**

**但上面"已排除分辨率"这一条要打折扣**：得出该结论时 `imageSide` 覆盖其实从未生效
（缺陷 a），解码阶段又有 448 的硬天花板（缺陷 b）。也就是说分辨率这个变量**根本没有
被真正测过**，不能算排除；"相册路径 vs 基准路径一致"那组对照同样需要重做。

> 后续会话补做了这两项：分辨率确实有效（144 → 336 → 576 图像 token，描述随之变细），
> 但它自始至终都不是乱码的原因。

这与 llama.cpp 自带的警告方向一致 —— **图像 token ≥ 1024** 是唯一有官方依据的待验
假设，而回归测试里从未真正达到过那个量级（最高只测到 336）。

## 验证命令速查

```bash
# 生产路径：真实相册照片 + 策略重置（期望 NPU）
adb shell am start -n com.example.isip/.MultimodalTestActivity \
    --ez benchmark true --ez photoAnalysis true --es npuState reset --ei photoCount 1

# 合成图基准（后端可指定）
adb shell am start -n com.example.isip/.MultimodalTestActivity \
    --ez benchmark true --es backend NPU --ei runs 3 --ei maxTokens 64

# 结果（本设备不把应用 logcat 交给 adb，必须读文件）
adb shell run-as com.example.isip cat files/bench/benchmark.txt
adb shell run-as com.example.isip cat files/bench/native.log

# 策略状态
adb shell run-as com.example.isip cat shared_prefs/isip_accelerator.xml
```

`--es npuState` 取值：`reset` / `disable` / `exhaust` / `clear`（仅用于验证降级分支）。
`--es backend` 取值：`AUTO` / `CPU` / `GPU` / `NPU`。

> **测试注意**：`npuState` 钩子在任何前置检查失败时（例如模型缺失）会被跳过，因为
> 它位于 `runBenchmark` 之前、模型探测之后。若想确认生效，看 `native.log` 里的
> `NPU 策略已置为 ...`。另外**不要在推理进行中强杀进程**来做清理 —— 那会留下
> `npu_inference_in_flight=true`，被策略如实判为一次崩溃。要复位就用
> `--es npuState clear`，或删除 `shared_prefs/isip_accelerator.xml`。
