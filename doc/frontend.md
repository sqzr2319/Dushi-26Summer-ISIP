# 个人相册整理智能体 - 前端开发文档

## 一、文档目标

本文档基于 `plan.md` 中的项目规划，细化 Android 前端部分的开发方案。前端需要承载“感知 → 理解 → 规划 → 执行 → 反馈”的智能体闭环体验，让用户能够浏览本地相册、发起文本或语音检索、查看 AI 分析过程、确认整理建议，并安全地执行批量操作。

前端开发范围包括：页面结构、导航流程、状态管理、组件拆分、交互规范、与业务/AI 模块的数据接口、测试验收标准。AI 模型推理、图片分析算法、数据库实现不属于前端直接实现范围，但前端需要为这些能力提供清晰的调用入口和可视化反馈。

## 二、技术选型

### 2.1 基础框架

- **开发语言**：Kotlin
- **UI 框架**：Jetpack Compose
- **设计组件**：Material 3
- **架构模式**：MVVM + Repository/UseCase
- **异步状态**：Kotlin Coroutines + Flow/StateFlow
- **图片加载**：建议使用 Coil for Compose
- **导航**：建议使用 Navigation Compose
- **权限处理**：Activity Result API
- **后台进度展示**：接入 WorkManager 状态或业务层 Flow

当前工程已启用 Compose、Material3 和 `ISIPTheme`，后续前端应优先基于 Compose 实现，不再新增 XML 页面，除非某些系统能力必须使用传统 View。

### 2.2 推荐依赖

在现有依赖基础上，前端建议补充：

```kotlin
implementation("androidx.navigation:navigation-compose:<latest>")
implementation("io.coil-kt:coil-compose:<latest>")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:<latest>")
implementation("androidx.lifecycle:lifecycle-runtime-compose:<latest>")
implementation("androidx.compose.material:material-icons-extended:<latest>")
implementation("com.google.accompanist:accompanist-permissions:<latest>") // 可选
```

如果项目希望减少第三方依赖，权限处理可以直接使用 Activity Result API，不强制引入 Accompanist。

## 三、前端设计原则

### 3.1 用户体验目标

1. **本地优先感知清晰**：用户能明确知道照片分析在本地进行，云端降级必须有授权与提示。
2. **处理过程可见**：批量分析、检索精排、重复检测、隐私识别都需要进度、当前任务和可取消入口。
3. **高风险操作必须确认**：删除、批量移动、清理重复照片、处理隐私照片不得自动执行。
4. **结果先可用再精细**：搜索采用“快速粗筛先展示，语义精排后更新”的体验，避免用户等待空白页面。
5. **移动端友好**：单手可操作，底部导航清晰，列表和网格滚动流畅。

### 3.2 视觉风格

- 使用 Material 3 作为基础视觉语言。
- 以“相册工具”体验为主，界面保持清爽、信息密度适中，不做营销式首页。
- 图片是核心内容，页面视觉重心应落在照片缩略图、分类结果、搜索结果和操作确认上。
- 隐私提醒使用明显但克制的警示色，避免误报时造成过度惊吓。
- 支持浅色/深色模式，默认跟随系统。

## 四、信息架构与页面导航

### 4.1 主导航结构

应用采用底部导航，包含四个一级入口：

| 页面 | 路由 | 目标 |
| --- | --- | --- |
| 相册 | `gallery` | 浏览本地照片、查看分析状态、进入照片详情 |
| 搜索 | `search` | 文本/语音自然语言检索照片 |
| 整理 | `organize` | 查看分类方案、重复照片、清理建议 |
| 设置 | `settings` | 权限、模型运行模式、隐私与缓存管理 |

建议扩展二级页面：

| 页面 | 路由 | 入口 |
| --- | --- | --- |
| 照片详情 | `photo/{photoId}` | 相册网格、搜索结果、整理结果 |
| 分析任务 | `analysis` | 相册页顶部状态、整理页入口 |
| 重复照片对比 | `duplicate/{groupId}` | 整理页重复照片卡片 |
| 隐私提醒详情 | `privacy/{alertId}` | 整理页隐私提醒卡片 |
| 批量操作确认 | `confirmAction` | 删除、移动、清理等操作前 |

### 4.2 启动流程

1. App 启动进入 `MainActivity`。
2. 检查媒体读取权限。
3. 未授权时展示权限说明页，并通过系统授权弹窗申请权限。
4. 授权后进入相册页，加载本地缩略图。
5. 如果没有分析结果，展示“开始分析”入口；如果已有后台任务，展示进度条和当前任务。
6. 用户可以随时切换到搜索、整理或设置页面。

## 五、核心页面设计

### 5.1 相册页 `GalleryScreen`

**页面目标**：展示本地照片，提供基础浏览、筛选、分析入口。

**主要内容**：

- 顶部栏：标题、分析状态入口、筛选按钮。
- 搜索入口：点击跳转搜索页，可显示示例查询占位文案。
- 分类筛选：全部、人物、风景、截图、文档、隐私提醒等。
- 照片网格：三列或四列自适应展示缩略图。
- 分析状态条：显示已分析数量、总数量、当前阶段、暂停/继续按钮。
- 空状态：无权限、无照片、无分析结果三类状态分别处理。

**交互要求**：

- 点击照片进入详情页。
- 长按照片进入多选模式。
- 多选模式下显示底部操作栏：删除、加入分类、重新分析、取消。
- 网格滚动需要保持流畅，缩略图加载失败时显示占位图。

**状态模型建议**：

```kotlin
data class GalleryUiState(
    val permissionGranted: Boolean = false,
    val photos: List<PhotoUiModel> = emptyList(),
    val selectedPhotoIds: Set<String> = emptySet(),
    val activeCategory: String = "全部",
    val analysisProgress: AnalysisProgressUi? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
```

### 5.2 照片详情页 `PhotoDetailScreen`

**页面目标**：展示单张照片和 AI 理解结果。

**主要内容**：

- 大图预览。
- 基础信息：拍摄时间、文件大小、来源相册。
- AI 分析结果：分类、标签、OCR 文本、自然语言描述、置信度。
- 风险提示：隐私内容、重复照片、低质量照片。
- 操作区：重新分析、加入整理计划、删除、分享。

**交互要求**：

- OCR 文本较长时支持展开/收起。
- 标签可点击，跳转到对应标签筛选结果。
- 删除必须进入确认弹窗或确认页。

### 5.3 搜索页 `SearchScreen`

**页面目标**：支持用户通过自然语言查找照片，并展示粗筛与精排过程。

**主要内容**：

- 顶部搜索框：文本输入、清空、语音输入按钮。
- 搜索建议：最近查询、常用场景，例如“上周的截图”“有发票的照片”。
- 结果区域：照片网格或瀑布流。
- 匹配解释：显示命中的标签、OCR 片段、时间条件。
- 精排状态：当后台语义排序进行中时显示轻量进度。
- 对话式细化：当结果太多或不理想时，允许追加条件。

**搜索体验流程**：

1. 用户输入查询并提交。
2. ViewModel 调用 `SearchUseCase.search(query)` 获取粗筛结果。
3. 页面立即展示粗筛结果和“正在优化排序”的状态。
4. 语义精排完成后局部更新结果顺序和匹配解释。
5. 用户点击结果进入照片详情，或继续补充条件。

**状态模型建议**：

```kotlin
data class SearchUiState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val results: List<SearchResultUiModel> = emptyList(),
    val isSearching: Boolean = false,
    val isReranking: Boolean = false,
    val conversation: List<SearchTurnUiModel> = emptyList(),
    val errorMessage: String? = null
)
```

### 5.4 整理页 `OrganizeScreen`

**页面目标**：将 AI 生成的整理策略变成用户可理解、可确认、可撤销的操作计划。

**主要内容**：

- 总览卡片：已分析照片数量、建议分类数量、重复照片数量、隐私提醒数量。
- 分类方案：按人物、风景、截图、文档、事件、时间分组展示。
- 重复照片：按相似组展示，突出推荐保留项。
- 隐私提醒：展示证件、银行卡、密码、聊天截图等敏感类型。
- 整理建议：例如创建相册、归档截图、清理重复照片。
- 批量确认入口：预览将要执行的操作。

**交互要求**：

- 每条建议都可以展开查看依据。
- 用户可以勾选/取消勾选某条建议。
- 重复照片对比页需要并排展示相似图片、相似度和推荐理由。
- 隐私提醒只做提示，默认不自动删除。
- 执行整理前展示确认页，执行后提供撤销入口。

**状态模型建议**：

```kotlin
data class OrganizeUiState(
    val plan: OrganizationPlanUiModel? = null,
    val selectedSuggestionIds: Set<String> = emptySet(),
    val isGeneratingPlan: Boolean = false,
    val actionPreview: BatchActionPreviewUi? = null,
    val lastActionUndoable: Boolean = false,
    val errorMessage: String? = null
)
```

### 5.5 设置页 `SettingsScreen`

**页面目标**：承载权限、隐私、模型模式、缓存清理等控制项。

**主要内容**：

- 权限状态：媒体读取权限、通知权限、麦克风权限。
- 推理模式：本地优先、云端降级、仅本地、仅云端。
- 模型状态：是否已加载、量化类型、加速方式、内存占用。
- 隐私控制：关闭云端降级、清除分析结果、敏感照片不上传。
- 缓存管理：分析缓存大小、清理入口。
- 关于与调试：版本号、Demo 模式、日志导出。

## 六、组件拆分

建议按功能域组织 Compose 组件：

```text
ui/
├── app/
│   ├── ISIPApp.kt
│   ├── MainNavHost.kt
│   └── ISIPBottomBar.kt
├── gallery/
│   ├── GalleryScreen.kt
│   ├── PhotoGrid.kt
│   ├── PhotoGridItem.kt
│   └── AnalysisStatusBar.kt
├── photo/
│   ├── PhotoDetailScreen.kt
│   ├── AnalysisResultPanel.kt
│   └── OcrTextBlock.kt
├── search/
│   ├── SearchScreen.kt
│   ├── SearchInputBar.kt
│   ├── SearchSuggestionChips.kt
│   └── SearchResultGrid.kt
├── organize/
│   ├── OrganizeScreen.kt
│   ├── CategoryPlanSection.kt
│   ├── DuplicateGroupCard.kt
│   ├── PrivacyAlertCard.kt
│   └── BatchActionPreviewSheet.kt
├── settings/
│   ├── SettingsScreen.kt
│   └── SettingRow.kt
└── common/
    ├── PermissionRequestContent.kt
    ├── LoadingState.kt
    ├── ErrorState.kt
    ├── EmptyState.kt
    └── ConfirmDialog.kt
```

### 6.1 通用组件要求

- `LoadingState`：用于全屏加载、局部加载和后台进度。
- `ErrorState`：显示错误原因和重试按钮。
- `EmptyState`：用于无照片、无搜索结果、无整理建议。
- `ConfirmDialog`：统一处理删除、清理缓存、关闭云端等确认场景。
- `PhotoGridItem`：必须提供稳定尺寸，避免缩略图加载导致网格抖动。

## 七、前端与业务层接口

### 7.1 ViewModel 划分

| ViewModel | 负责页面 | 依赖用例 |
| --- | --- | --- |
| `GalleryViewModel` | 相册页 | `PhotoRepository`、`AnalysisUseCase` |
| `PhotoDetailViewModel` | 照片详情页 | `PhotoRepository`、`AnalysisUseCase` |
| `SearchViewModel` | 搜索页 | `SearchUseCase` |
| `OrganizeViewModel` | 整理页 | `OrganizationUseCase` |
| `SettingsViewModel` | 设置页 | `SettingsRepository`、`ModelStatusProvider` |

### 7.2 UI 数据模型

前端不直接暴露数据库实体或 AI 原始输出，建议转换为 UI Model：

```kotlin
data class PhotoUiModel(
    val id: String,
    val uri: String,
    val takenAtText: String,
    val categories: List<String>,
    val tags: List<String>,
    val hasPrivacyAlert: Boolean,
    val isAnalyzed: Boolean
)

data class SearchResultUiModel(
    val photo: PhotoUiModel,
    val relevanceScoreText: String,
    val matchedTags: List<String>,
    val matchedText: String?
)

data class AnalysisProgressUi(
    val total: Int,
    val completed: Int,
    val currentTaskText: String,
    val progress: Float,
    val canPause: Boolean,
    val canCancel: Boolean
)
```

### 7.3 事件处理

推荐每个页面使用单向数据流：

```kotlin
sealed interface GalleryUiEvent {
    data object RequestPermission : GalleryUiEvent
    data object StartAnalysis : GalleryUiEvent
    data object PauseAnalysis : GalleryUiEvent
    data object ResumeAnalysis : GalleryUiEvent
    data class SelectCategory(val category: String) : GalleryUiEvent
    data class TogglePhotoSelection(val photoId: String) : GalleryUiEvent
    data class OpenPhoto(val photoId: String) : GalleryUiEvent
}
```

页面只负责发送事件，ViewModel 负责调用业务层并更新 `UiState`。

## 八、权限与隐私交互

### 8.1 权限清单

| 权限 | 用途 | 触发时机 |
| --- | --- | --- |
| `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` | 读取本地相册 | 首次进入相册页 |
| `RECORD_AUDIO` | 语音输入 | 首次点击语音按钮 |
| `POST_NOTIFICATIONS` | 后台分析进度通知 | 开启后台分析时 |

Android 13 及以上使用 `READ_MEDIA_IMAGES`，Android 12 及以下使用 `READ_EXTERNAL_STORAGE`。

### 8.2 隐私提示规范

- 云端 API 降级必须在设置中明确开启。
- 涉及身份证、银行卡、密码等隐私提醒时，前端只展示风险与建议，不自动执行删除或上传。
- 用户清除分析结果时，需要说明清除的是标签、OCR、描述和缓存，不删除原图。
- 批量删除需要展示照片数量、可预览缩略图，并要求二次确认。

## 九、关键交互流程

### 9.1 首次分析流程

1. 用户授权读取相册。
2. 相册页加载照片缩略图。
3. 用户点击“开始分析”。
4. 前端展示分析进度、已完成数量、当前照片或当前阶段。
5. 分析结果持续写入本地数据库，页面增量更新标签与分类。
6. 分析完成后提示可查看整理建议。

### 9.2 搜索流程

1. 用户输入“上周末拍的海边日落照片”。
2. 前端提交查询到 `SearchViewModel`。
3. 页面先显示快速匹配结果。
4. 后台完成语义理解和精排后更新排序。
5. 用户点击照片进入详情，查看匹配依据。

### 9.3 整理确认流程

1. 用户进入整理页。
2. 页面展示分类方案、重复照片和隐私提醒。
3. 用户勾选需要执行的建议。
4. 点击执行前进入批量操作预览。
5. 用户确认后调用业务层执行。
6. 执行完成后展示结果，并在可撤销时间内提供撤销。

## 十、状态与异常处理

### 10.1 页面状态

每个页面至少需要覆盖以下状态：

- `Loading`：加载照片、加载分析结果、生成整理计划。
- `Content`：正常展示内容。
- `Empty`：无照片、无搜索结果、无建议。
- `Error`：权限失败、读取失败、推理失败、网络失败。
- `PartialSuccess`：部分照片分析失败，但整体任务可继续。

### 10.2 错误提示

| 场景 | 前端处理 |
| --- | --- |
| 无相册权限 | 展示权限说明和授权按钮 |
| 用户拒绝权限 | 保留重试入口，说明无法读取照片 |
| 本地模型加载失败 | 展示失败原因，提供重试或云端降级入口 |
| 搜索无结果 | 展示修改查询建议 |
| 批量删除失败 | 展示失败数量和可重试项 |
| 后台任务被系统中断 | 展示可恢复入口 |

## 十一、性能要求

- 相册首屏缩略图加载目标：1 秒内出现可见内容。
- 网格滚动目标：保持接近 60 FPS，避免在 Composable 中进行重型计算。
- 搜索粗筛结果目标：200ms 内展示。
- 大图详情页需要按需加载，离开页面及时释放不必要状态。
- 长列表必须使用 `LazyVerticalGrid` / `LazyColumn`。
- 分析进度更新需要节流，避免高频状态刷新导致 UI 卡顿。
- 缩略图使用固定尺寸和占位图，减少布局跳动。

## 十二、测试计划

### 12.1 单元测试

- ViewModel 状态流转测试。
- 搜索事件处理测试。
- 批量操作确认逻辑测试。
- UI Model 转换测试。

### 12.2 Compose UI 测试

- 权限未授权状态展示。
- 相册网格正常展示照片。
- 搜索输入后展示结果和精排状态。
- 整理页可勾选建议并进入确认页。
- 删除确认弹窗必须出现。

### 12.3 真机测试

- Android 10 至 Android 14 权限兼容性。
- 不同屏幕尺寸下底部导航、网格列数和详情页布局。
- 大量照片场景下滚动性能。
- 深色模式可读性。
- 语音输入权限和失败回退。

## 十三、前端开发里程碑

### 第一阶段：基础界面与导航

- [ ] 替换默认 `Greeting` 页面，创建 `ISIPApp`。
- [ ] 接入底部导航和主路由。
- [ ] 完成相册页静态布局。
- [ ] 完成权限申请和无权限状态。
- [ ] 完成照片网格基础展示。

### 第二阶段：相册与分析状态

- [ ] 接入本地照片数据源。
- [ ] 完成照片详情页。
- [ ] 展示分类、标签、OCR 和描述结果。
- [ ] 接入分析进度状态。
- [ ] 支持开始、暂停、继续和取消分析。

### 第三阶段：搜索体验

- [ ] 完成搜索页输入框和语音入口。
- [ ] 接入搜索用例。
- [ ] 展示搜索结果和匹配依据。
- [ ] 支持搜索建议和历史记录。
- [ ] 支持对话式追加条件。

### 第四阶段：整理与确认

- [ ] 完成整理总览。
- [ ] 完成分类方案展示。
- [ ] 完成重复照片对比。
- [ ] 完成隐私提醒展示。
- [ ] 完成批量操作预览、确认和撤销。

### 第五阶段：优化与验收

- [ ] 完善错误、空状态和加载状态。
- [ ] 完成深色模式适配。
- [ ] 完成 Compose UI 测试。
- [ ] 完成多设备真机测试。
- [ ] 准备 Demo 演示流程。

## 十四、验收标准

前端部分达到以下标准即可认为完成：

- 用户可以授权读取相册并浏览照片。
- 用户可以查看照片的分类、标签、OCR 和描述结果。
- 用户可以通过自然语言搜索照片，并看到匹配依据。
- 用户可以查看整理方案、重复照片建议和隐私提醒。
- 删除、清理、批量整理等高风险操作都有明确确认流程。
- 后台分析和搜索精排过程有清晰进度反馈。
- 权限拒绝、模型失败、无结果、部分失败等异常都有可恢复体验。
- 页面在主流 Android 设备上布局正常、滚动流畅、深色模式可读。

---

**文档版本**：v1.0  
**创建日期**：2026-07-10  
**依据文档**：`plan.md`
