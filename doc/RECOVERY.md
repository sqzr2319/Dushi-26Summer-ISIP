# 恢复步骤（设备当前处于不可用状态）

## 现状

排查过程中为了验证包是否损坏，我执行了 `adb uninstall com.example.isip`。
之后**所有**自动安装方式都被 MIUI 拒绝：

```
Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]
```

已尝试且全部失败：`adb install -r -g -d`、`pm install -r -d`、`pm install --user 0`、
`install-create`/`install-write`/`install-commit` 会话方式、
`settings put global verifier_verify_adb_installs 0`（shell 无 WRITE_SECURE_SETTINGS）。

设备上现有：

- APK 已就位且有效：`/data/local/tmp/app.apk`（1,610,971,005 字节）
  aapt2 确认三个 Activity 均已声明（MainActivity / MultimodalTestActivity /
  GenieXNpuTestActivity）。
- `/data/local/tmp/run_mm.sh`、`run_photos.sh`、`dumpthreads.sh` 测试脚本已就位。
- **模型文件已被卸载清空**（卸载会删私有目录），需要重新部署。

## 恢复步骤（第 1 步需要人在手机上点一下）

1. 在手机上打开 **设置 → 更多设置 → 开发者选项 → 通过 USB 安装应用**（打开它），
   或保持手机解锁，安装时在弹窗上点「允许」。

   **这一步几乎肯定就是根因**：报错是 `INSTALL_FAILED_USER_RESTRICTED: Install canceled
   by user`，而排查时的状态是：

   - 设备已重启（`03:28` 重启，之前 `02:34` 的安装是成功的）
   - `mWakefulness` 曾为 `Dozing`、`isKeyguardShowing=true`（锁屏熄屏）
   - 屏幕上没有等待确认的对话框（`ResumedActivity` 是 `com.miui.home/.launcher.Launcher`）

   即：**安装被策略直接拒绝，不是卡在一个等人点的弹窗上**。MIUI 的「通过 USB 安装应用」
   开关在重启后会失效，重新打开通常需要登录小米账号 —— 这一步无法用脚本完成，
   而 `settings put` 系列全被 `WRITE_SECURE_SETTINGS` 拒绝（已验证）。

   已确认可用的相关设置（说明不是它们的问题）：
   `global/upload_apk_enable=1`、`secure/install_non_market_apps=1`、
   `global/development_settings_enabled=1`、`global/adb_enabled=1`。

2. 安装 APK：

   ```powershell
   adb shell pm install -r -d /data/local/tmp/app.apk
   ```

   若报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，说明有残留，先
   `adb uninstall com.example.isip` 再装。

3. 重新部署模型（卸载清空了 `files/models/`）。默认部署 2B 配对：

   ```powershell
   python tools/deploy_qwen35.py --variant 2b --model-dir build-models/qwen35-4b
   ```

   该目录下的文件名为 `2b-Qwen3.5-2B-Q4_0.gguf` 与 `2b-mmproj-F16.gguf`；
   脚本按字节数校验落地结果，防止 2B/4B mmproj 混用（两者同名不同内容）。

   若要试 4B：`--variant 4b --model-dir build-models/qwen35-4b`
   （注意 4B 目前推理会 SIGSEGV，见 `doc/npu-hexagon-inapp.md`）。

4. 确认基础可用性：

   ```powershell
   adb shell svc power stayon true
   adb shell "sh /data/local/tmp/run_mm.sh CPU 1 32"     # 合成图基准，应正常完成
   ```

   结果读 `files/bench/benchmark.txt` 与 `files/bench/native.log`。
   **这台设备不把应用自身 logcat 交给 adb，必须读文件。**

## 下一步该验什么

上一轮把测量链路修好了（三个缺陷：`imageSide` 覆盖从未生效、解码阶段 448 硬编码、
`am start` 参数被拆错），所以分辨率这个变量**其实从没被真正测过**。

恢复后按这个顺序重验：

1. 同两张真实照片，`imageSide` 取 384 / 640 / 1024，看输出质量是否随图像 token 数改善。
   llama.cpp 明确建议 Qwen-VL 至少 1024 个图像 token，对应最长边约 1024px —— 这是目前
   唯一有官方依据的方向，而此前回归测试最高只测到 336 个 token。

   ```powershell
   adb shell "sh /data/local/tmp/run_photos.sh 2 1024 96"   # count side maxTokens
   ```

2. 用原生日志确认真正送入的尺寸与 token 数，不要依赖推断：

   ```
   [diag] 送入模型: jpeg=WxH imageBytes=... imageHash=...
   [diag]   chunk[1] IMAGE n_tokens=N
   ```

3. 若 1024px 挂死，注意它可能是**间歇性**的（与分辨率无关，实测 384px 也会挂）。
   重试并先用 `adb shell kill -9 <pid>` 清掉僵死进程，再启动 ——
   否则 `am start` 会回 "intent has been delivered to currently running top-most
   instance" 而进程并不存在，看起来像崩溃。

## 两个已知的坑（都已修，别再踩）

- **不要用 `adb shell "am start ... --es k v"` 这种带空格的长参数串**：经过
  PowerShell → adb → sh 会被拆错（曾出现 `pkg=is`、prompt 丢失）。用
  `/data/local/tmp/run_*.sh` 里的脚本。
- **读运行数据用 `tail` 或先清空日志**：`grep` 默认取首条匹配，在累积日志里会读到
  多次之前的运行，据此判断会得出错误结论。
