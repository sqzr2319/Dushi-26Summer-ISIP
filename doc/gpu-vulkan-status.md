# GPU（Vulkan / Adreno 830）路线结论

设备：小米 15（24129PN74C），SM8750 骁龙 8 Elite，Android 16，Adreno 830。
模型：`Qwen3.5-2B-Q4_0.gguf`（1.21GB）+ `mmproj-F16.gguf`。

> ## ⛔ 结论（已更新）：Vulkan 路线终止，原因从"不稳定"改为"算错"
>
> 本文档最初把 Vulkan 的问题记为**运行时挂死/不稳定**，并建议"不建议继续投入"。
> 后续会话补做了**输出正确性**检查，结论更严重也更明确：
>
> **Vulkan 能跑完，但算出来的数是错的。** 它产出结构完整（JSON 字段齐全）、
> 内容完全乱码的文本。这比挂死危险得多 —— 挂死会暴露，算错会静默污染生产数据。
>
> 因此 `AUTO` 自动回落链已改为 **NPU -> CPU**，Vulkan 被移除。
> 它仍可由 `--es backend GPU` 显式指定，仅用于继续排查。
>
> 详见下面"运行时正确性"一节。

## 已完成的工作

Vulkan 后端**可以正确构建并注册**，这部分是确定可用的：

- 修复了 CMakeLists 里后端源码被 `list(FILTER ... EXCLUDE ... hexagon ...)` 连坐排除的问题。
- 用 Vulkan SDK 1.4.357.0 重新生成了全套 **1323 个 shader**（原预生成产物残缺，无法链接）。
- 补齐了上游生成器**声明却从不产出**的 **160 个聚合表符号**
  （`arr_dmmv_*` 75 组 + `add/sub/mul/div/add_rms` 5 组），
  见 `app/src/main/cpp/vulkan-shaders/shader-cpps/gen_arr_dmmv_tables.ps1`。
- `libllama-jni.so` 92MB → 134MB，导出 `ggml_backend_vk_reg`，链接 `libvulkan.so`。
- 设备上实测上报：`Vulkan: 1 device(s) [Vulkan0 19209MiB]; CPU: 1 device(s) [CPU 15113MiB]`。

## 运行时正确性：Vulkan 会静默算错（本轮新结论）

### 实验设计

同一张真实相册照片（香港维港，4096x2304 原图）、同一提示词
（`用一句话描述这张照片的内容。`）、同一图像 token 数（640x360 -> 220 tokens）、
`temperature = 0`（贪心，保证可比），只改后端：

### 结果

| 后端 | 纯文本（"请从1数到40"） | 多模态（同一张真实照片） |
| --- | --- | --- |
| **CPU** | `1,2,3,4,5,6,7,8,9,10,11,12,13,14` ✅ | `这张照片展示了香港维多利亚港的繁华天际线，背景是密集的高楼大厦和阴天的天空。` ✅ |
| **Vulkan** | `auuderach风光착位ูน legalizeitary pilуд_suffixombo佈endsums…` ❌ | `(string谅averseyách述ilarlochits藤得力urant CÁ务盾brasies葫…` ❌ |

**关键观察：乱码不是多模态特有的。** 纯文本、无图片、贪心采样，在 Vulkan 上同样是
乱码。而输出里的 JSON **结构**完全正确（`{"categories":[…],"tags":[…],"ocr_text":"",
"description":"…","confidence":0.9,…}` 字段一个不少），说明模型在跑、只是在算错。

### 层数定位：0 层正确，4 层就错

用 `--ei gpuLayers N` 控制卸载层数（`Vulkan0 + CPU` 两个设备始终都在）：

| gpuLayers | 输出 |
| --- | --- |
| **0** | ✅ `这张照片展示了香港维多利亚港的繁华天际线，背景是密集的高楼大厦和阴天的天空。` |
| 4 | ❌ `凸：静态丘和分析/静态丘分析;` |
| 12 | ❌ `filtrحاب back guarinconouasi弥อด…USEDارت未来…` |
| 24 | ❌ `6arityнес稿2xtiskendersenetgments制ukunft汽车有限公司…` |

0 层正确 ⇒ **设备选择、调度器拓扑、混合（gated-delta-net + full attention）架构的
状态管理都没问题**；一旦有算子真的落到 Vulkan 上就错 ⇒ 问题在 **Vulkan 的算子实现**。

### 已排除的规避开关（全部无效）

后端初始化路径上都读环境变量，而 App 进程只能靠 JNI `setenv`。为此加了
`--es vkFlags "DISABLE_FUSION,DISABLE_ASYNC,…"`（自动补 `GGML_VK_` 前缀）
与 `--ei flashAttn 0|1|2` 两个开关：

| 开关 | 结果 |
| --- | --- |
| `GGML_VK_DISABLE_FUSION` | 乱码**变了**（说明机制生效）但仍乱码 |
| `GGML_VK_DISABLE_ASYNC` | 与基线逐字节相同的乱码 |
| `GGML_VK_DISABLE_COOPMAT,DISABLE_COOPMAT2` | 与基线逐字节相同的乱码 |
| `GGML_VK_DISABLE_MMVQ` | 与基线逐字节相同 |
| `GGML_VK_FORCE_MMVQ` | 与基线逐字节相同 |
| `GGML_VK_DISABLE_GRAPH_OPTIMIZE` | 乱码**变了**但仍乱码 |
| flash attention 强制关闭 / 强制开启 | 两者都乱码 |

"换个开关乱码就换一副面孔"是单点 bug 的典型特征被排除的信号 —— 多个算子路径都有
问题，或者问题在其共用的底层（内存布局 / 同步 / 量化反量化）。**没有单个开关能救。**

模型侧的相关事实（说明算子确实被 Vulkan 接管而非回落 CPU）：`ggml-vulkan.cpp`
对 `GGML_OP_GATED_DELTA_NET` 有完整实现，而本模型的 `ssm.state_size = 128`
正好落在它支持的 `{16,32,64,128}` 桶里，因此不会触发 CPU 回落。

## 历史：运行时挂死（早期观察）

早期（不同构建/参数组合下）Vulkan 曾表现为挂死，失败点随运行变化：

| gpuLayers | 结果 | 卡住的位置 |
| --- | --- | --- |
| 8 | 挂死 | `mtmd_helper_eval_chunks` |
| 16 | 挂死 | `mtmd_helper_eval_chunks` |
| 24 | 挂死 | 更早：`llama_init_from_model` 完成后无输出 |
| 999 | 挂死 | `mtmd_helper_eval_chunks` |

挂死的表现：CPU 占用 0.0%、进程存活、内存常驻约 1.6–2.2GB、所有线程处于睡眠、
无 futex 争用 —— 阻塞在 GPU 同步点，而非计算。

> **对早期记录的更正**：当时写"纯文本生成曾在 GPU 上成功过一次（999 层、16 token、
> 4.28 tok/s）"—— 那次"成功"只检查了**它返回了**，没有检查**返回了什么**。
> 按本轮的结论，那段文本极可能也是乱码。**"跑通了"不等于"算对了"**，
> 这是本项目最贵的一次教训。

设备上另有一个次要的稳定性问题：内存吃紧时（本机 15.5GB RAM，压测下常驻仅剩约
340MB 可用、swap 已用 3.6GB）会看到

```
ggml_backend_sched_alloc_splits: failed to allocate graph, reserving (backend_ids_changed = 1)
```

并可能长时间无进展。

## 关键结构性事实

`glslc` 对全部 7 个可选扩展都支持（coopmat / coopmat2 / coopmat2_decode_vector /
integer_dot / bfloat16 / float_e2m1 / float_e4m3）。当前在用的 shader 集是在
**这些宏全部关闭**的情况下生成的，这不是配置疏漏，而是一个保守自洽的选择：

- `set_rows` 的生成处**不在任何 `#if` 内**（`vulkan-shaders-gen.cpp:831-834`），
  所以它缺失与扩展宏无关 —— 是上游生成器自身的缺口（已由本项目的补丁脚本解决）。
- 若改为启用全部扩展，变体数会从 **1323 增至 1827**，多出的 504 个全部是
  `_cm1` / `_cm2` / `_dot2` / `_ocp` / `_int8` 这类**扩展专属路径**，
  会让后端启用 Adreno 的 cooperative-matrix 路径。
- 因此"启用扩展"不会缩小现有 shader，只会引入新的硬件加速代码路径，
  大概率让不稳定性更糟（Adreno + coopmat 是已知的敏感组合）。

> 该判断与本轮结果**不矛盾但已不足以解释**：本轮用的是保守 shader 集（扩展全关），
> 仍然算错。所以问题不在"没用到 coopmat"，而在已启用的基础路径里。
> `DISABLE_FUSION` / `DISABLE_GRAPH_OPTIMIZE` 会改变乱码内容，指向图优化与融合后的
> 内存/同步假设，值得作为下一步的入口。

## 结论

**不建议继续在 GPU 路线上投入。** 后端本身已正确集成（构建、注册、设备可见都成立），
但在这台 SM8750 上**计算结果不可信**，且没有任何单一规避开关能修复 —— 这属于
驱动/后端层面的问题，不是本项目代码可以可靠绕开的。

**它已被移出 `AUTO` 自动回落链。** 保留 `backend = GPU_VULKAN` 仅供继续排查，
生产路径绝不使用它：静默的错误输出比明确的失败更有害。

若将来要重试，值得尝试的方向（按性价比排序）：

1. **预编译 Vulkan pipeline cache** 并随 APK 分发，避开运行时大规模 pipeline 编译。
2. **升级 llama.cpp 到更新的 revision**（当前 checkout：`6eddde0`，2026-07-13），
   重点看 `ggml-vulkan` 的 `gated_delta_net` / `ssm_*` / 融合相关修复。
3. 在**最小可复现图**上做逐算子对拍（同一输入，CPU 与 Vulkan 各自输出张量，逐元素比），
   定位到具体算子；`GGML_VULKAN_OUTPUT_TENSOR` 环境变量可用于导出中间张量。
4. 注意：`--ei flashAttn` 与 `--es vkFlags` 两个开关已经就位，无需再改代码即可继续排除。

## 当前可用的替代方案

**CPU 与 Hexagon NPU 两条路径都已验证可正确描述真实照片**（NPU 优先，失败自动回落 CPU）：
见 `doc/npu-app-integration.md`。生产路径实测（2B-Q4_0，NPU，768px，maxTokens 512）：

| 指标 | 值 |
| --- | --- |
| 单张真实照片（768x432，336 图像 token，约 160–200 生成 token） | **59–65 s**（稳态） |
| 其中视觉塔（CPU，无法上 NPU） | 约 34 s |
| 其中 decode（NPU） | 15–19 s（9.4–12.9 tok/s） |
| 权重驻留 | `HTP0-REPACK` 380.46 MiB，offloaded 25/25 层 |

冷启动第一张约 252 s（视觉塔 668 MB 首次 mmap，页缓存冷）；超时因此定在 360 s。
（4B-Q4_0 同口径为 127–165 s/张、951 MiB 驻留，因太慢退居次选。）

输出示例（同一张维港照片）：

```
标签: [#香港, #维多利亚港, #天际线, #城市景观]
描述: 这张照片展示了香港繁华的城市风光，前景是维多利亚港的水面和游船，
      背景是密集的高楼大厦和山峦。
置信度: 0.92
```

> 对照：**同一张照片走 Vulkan 得到的是一串多语言乱码。** 这就是把 Vulkan 移出
> 自动回落链的全部理由 —— 慢可以接受，错不行。

## 相关工具

- `tools/verify-production.ps1` —— **生产路径一键回归**：NPU 优先 + 两条回落分支，
  每个场景同时记录"实际生效的后端"与"输出的描述文本"。本文件的正确性数据就来自
  它的等价手工步骤。用法见 `doc/npu-app-integration.md`。
- `tools/run_photos.sh` —— 设备端启动脚本（必须放设备上跑，见下）。
- `build-models/sweep-gpu-layers.ps1` —— 层数扫描（本文件早期数据来源）。
- `--es backend CPU|GPU|NPU` + `--ei gpuLayers N` + `--ei flashAttn 0|1|2`
  + `--es vkFlags "…"` —— 本文件正确性数据的来源，对相册路径同样生效。
- 原生日志：`adb shell run-as com.example.isip cat files/bench/native.log`
  （设备不把应用 logcat 交给 adb，必须以文件为准）。

> ⚠️ 两条踩过的坑：
> 1. **不要把中文 grep 模式传进 adb** —— 会在 sh 层炸成 `no closing quote`。
>    用 `cat` 取回主机再过滤。
> 2. **`am start` 必须写成设备端脚本**。extras 过 adb → PowerShell → sh 会丢引号
>    （实测包名变成 `is`、prompt 被丢弃），而失败的样子和应用崩溃一模一样。
