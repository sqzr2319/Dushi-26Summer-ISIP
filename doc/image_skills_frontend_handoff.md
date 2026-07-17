# 图片整理 Skills 完成状态与前端交接

## 1. 已经完成部分

以下模块的领域逻辑和调用入口已经完成：

| 模块                       | 已完成能力                                               | 前端入口                                         |
| -------------------------- | -------------------------------------------------------- | ------------------------------------------------ |
| `AnalyzeImageSkill`        | 合并粗分类/详细分析、失败降级、统一分析结果              | `AnalyzePhotosUseCase`、相册页和详情页 ViewModel |
| `GenerateStrategySkill`    | 生成相册候选、重复候选、相似照片、标签建议、隐私提醒     | `OrganizePhotosUseCase`、`OrganizeViewModel`     |
| `FindDuplicateVideosSkill` | 哈希、可插拔相似度、元数据去重、推荐保留和空间统计       | `FindDuplicateVideosUseCase`                     |
| `ReviewCleanupSkill`       | 保留项覆盖、保护项过滤、低置信度警告、删除清单和空间统计 | `CleanupCoordinatorUseCase`                      |
| `DeletePhotoSkill`         | 精确 ID、一次性确认令牌、系统删除结果同步                | `GalleryViewModel`、`OrganizeViewModel`          |
| `SummarizeSelectionSkill`  | 数量、空间、日期、分类、标签、分析覆盖率、隐私数量       | `GalleryUiEvent.ShowSelectionSummary`            |

删除流程已经固定为：

```text
GenerateStrategySkill
  → ReviewCleanupSkill
  → DeletePhotoSkill.DeleteRequest
  → 前端展示确认
  → Android 系统确认
  → CleanupConfirmationResult
  → 删除并同步数据库
```

## 2. GenerateStrategySkill 的跨组端口

以下 Skill 由其他同学负责，`GenerateStrategySkill` 已保留可注入端口：

| 其他同学模块             | 对接端口                     | 返回内容              |
| ------------------------ | ---------------------------- | --------------------- |
| `SemanticSearchSkill`    | `SemanticSearchSkillPort`    | `SemanticGroup`       |
| `CreateSmartAlbumSkill`  | `CreateSmartAlbumSkillPort`  | `EventAlbum` 候选预览 |
| `AddTagSkill`            | `AddTagSkillPort`            | `TagSuggestion`       |
| `FindDuplicatesSkill`    | `FindDuplicatesSkillPort`    | `DuplicateGroup`      |
| `FindSimilarPhotosSkill` | `FindSimilarPhotosSkillPort` | `SimilarPhotoGroup`   |

端口不存在或调用失败时会自动使用本地分类/CLIP 降级，不会阻塞整理方案生成。

注意：`GenerateStrategySkill` 是只读规划器。`CreateSmartAlbumSkillPort` 在这里仅生成相册预览，真实创建必须由该同学的执行 Skill 在用户确认后完成；`AddTagSkill` 同理。

## 3. 前端必须完成的任务

### P0：重复照片复核与删除

1. 实现 `DuplicateScreen`，展示同组照片、相似度和推荐保留项。
2. 用户修改保留项后发送：

   ```kotlin
   OrganizeUiEvent.ReviewDuplicateCleanup(
       selectedCandidateIds = setOf(groupId),
       keepOverrides = mapOf(groupId to keepPhotoId),
       protectedPhotoIds = protectedIds
   )
   ```

3. 使用 `OrganizeUiState.cleanupReview` 展示：
   - 保留照片
   - 待删除照片
   - 预计释放空间
   - 低置信度和保护警告
4. 用户确认复核内容后发送 `RequestReviewedDeletion`。
5. 监听 `pendingDeleteRequest`，调用 `MediaStoreDeleteConfirmation.createIntentSender()` 拉起系统删除窗口。
6. 将系统结果回传：

   ```kotlin
   OrganizeUiEvent.CleanupConfirmationResult(
       requestId = requestId,
       approved = approved,
       systemDeleteCompleted = approved
   )
   ```

### P0：整理页内容补齐

1. 展示 `OrganizationPlan` 的五类结果：相册候选、重复照片、相似照片、标签建议、隐私风险。
2. 接通 `DuplicateScreen`、`PrivacyScreen`，不能再保留空导航页面。
3. 展示 `isExecutingCleanup`、`errorMessage` 和空结果状态。
4. “执行整理”按钮必须先展示复核，不可直接调用删除。

### P1：选择摘要

1. 相册多选工具栏增加“摘要”按钮。
2. 点击后发送 `GalleryUiEvent.ShowSelectionSummary`。
3. 使用 `GalleryUiState.selectionSummary` 显示数量、空间、分类、标签、已分析/未分析数量。
4. 关闭弹窗发送 `DismissSelectionSummary`。

### P1：重复视频

1. 申请 `READ_MEDIA_VIDEO` 运行时权限。
2. 调用 `FindDuplicateVideosUseCase.execute()`。
3. 展示视频缩略图、时长、大小、相似度、推荐保留项和预计释放空间。
4. 视频删除需要单独的 `MediaStore.Video` 删除网关，不能把视频 ID 传给 `DeletePhotoSkill`。

### P1：隐私提醒

1. `PrivacyScreen` 展示风险类型、命中标签/OCR 和原图。
2. 允许用户选择“保护”“忽略”或“进入删除复核”。
3. 保护的 ID 必须通过 `protectedPhotoIds` 传入复核流程。

## 4. 关于真实相册、隐私处理和撤销

- **真实相册创建**：端口已经预留；待 `CreateSmartAlbumSkill` 合入后即可在同一确认流程执行，不需要修改 `GenerateStrategySkill`。
- **隐私照片处理**：本模块已完成检测、提醒、保护和确认删除。加密、移动到私密目录不属于当前六个 Skill，需要产品确定存储方式后由独立执行 Skill 完成。
- **撤销**：当前使用永久删除，Android 系统确认完成后不能恢复。前端暂时不能显示“撤销成功”。如果产品需要撤销，应改成 `MediaStore.createTrashRequest()` 的回收站流程，再提供恢复操作。

## 5. 前端验收标准

- 没有用户确认时，不会产生任何删除。
- 系统删除窗口中的照片 ID 与复核页完全一致。
- 取消确认后照片和数据库均不变化。
- 删除成功后整理页、相册页立即刷新。
- 旋转屏幕不会重复创建删除请求。
- 相册、标签、相似/重复照片协作端口未接入时，页面仍能显示本地降级结果。
