# MobileCLIP 端侧模型配置

项目使用 MobileCLIP-S2 的图片、文本双编码器和 LiteRT。权重约 400 MB，不提交 Git、
不打进 APK，由调试设备安装脚本部署到 App 私有目录。

在 Android Studio Terminal 中执行：

```powershell
python tools/prepare_mobileclip.py
```

如果连接了多个设备：

```powershell
python tools/prepare_mobileclip.py --serial emulator-5554
```

安装后强制停止并重新打开 App，再点击“开始分析”。首次分析会为照片生成归一化
embedding；后续文本搜索和相似照片检测直接读取缓存，不会重复运行图片编码器。

运行要求：debug 包已安装、设备已授权 `adb`、应用已获得照片访问权限。
