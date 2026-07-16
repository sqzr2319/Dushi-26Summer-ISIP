package com.example.isip.domain.skill

import java.util.UUID

/**
 * Two-phase photo deletion. [execute] only creates a confirmation request;
 * deletion is possible only through [confirm] with the matching request token.
 */
class DeletePhotoSkill(
    private val deletionGateway: ConfirmedPhotoDeletionGateway,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() }
) : Skill<DeletePhotoSkill.Input, DeletePhotoSkill.DeleteRequest> {

    data class Input(
        val photoIds: List<String>,
        val reason: String = "用户选择清理照片"
    )

    data class DeleteRequest(
        val requestId: String,
        val photoIds: List<String>,
        val reason: String,
        val requiresConfirmation: Boolean = true
    )

    data class Confirmation(
        val requestId: String,
        val approved: Boolean
    )

    data class DeleteResult(
        val requestedIds: List<String>,
        val deletedIds: List<String>,
        val failedIds: List<String>,
        val cancelled: Boolean = false
    )

    fun interface ConfirmedPhotoDeletionGateway {
        /** Called only after this Skill has validated an explicit user confirmation. */
        suspend fun deleteAfterUserConfirmation(photoIds: List<String>): DeleteResult

        /** Records files already deleted by Android's system confirmation UI. */
        suspend fun recordSystemDeletion(photoIds: List<String>): DeleteResult = DeleteResult(
            requestedIds = photoIds,
            deletedIds = photoIds,
            failedIds = emptyList()
        )
    }

    private val pendingRequests = mutableMapOf<String, DeleteRequest>()

    override suspend fun execute(input: Input): DeleteRequest =
        requestDeleteAfterConfirmation(input.photoIds, input.reason)

    @Synchronized
    fun requestDeleteAfterConfirmation(
        photoIds: List<String>,
        reason: String = "用户选择清理照片"
    ): DeleteRequest {
        val ids = photoIds.filter(String::isNotBlank).distinct()
        require(ids.isNotEmpty()) { "至少需要一个有效 photoId" }
        val request = DeleteRequest(requestIdFactory(), ids, reason.trim())
        check(request.requestId.isNotBlank()) { "requestId 不能为空" }
        pendingRequests[request.requestId] = request
        return request
    }

    suspend fun confirm(
        confirmation: Confirmation,
        deletionCompletedBySystem: Boolean = false
    ): DeleteResult {
        val request = synchronized(this) { pendingRequests.remove(confirmation.requestId) }
            ?: throw IllegalArgumentException("删除确认请求不存在或已经处理")
        if (!confirmation.approved) {
            return DeleteResult(
                requestedIds = request.photoIds,
                deletedIds = emptyList(),
                failedIds = emptyList(),
                cancelled = true
            )
        }
        if (deletionCompletedBySystem) {
            return validateGatewayResult(
                request,
                deletionGateway.recordSystemDeletion(request.photoIds)
            )
        }
        return validateGatewayResult(
            request,
            deletionGateway.deleteAfterUserConfirmation(request.photoIds)
        )
    }

    private fun validateGatewayResult(
        request: DeleteRequest,
        result: DeleteResult
    ): DeleteResult {
        require(result.requestedIds.toSet() == request.photoIds.toSet()) {
            "删除网关返回了与确认请求不一致的媒体集合"
        }
        return result.copy(
            deletedIds = result.deletedIds.filter { it in request.photoIds }.distinct(),
            failedIds = result.failedIds.filter { it in request.photoIds }.distinct()
        )
    }

    override fun getToolDescription(): String = """
        |## 工具名称
        |request_delete_after_confirmation
        |## 功能
        |为指定 photoIds 创建删除确认请求。只有用户使用匹配 requestId 明确确认后才调用系统删除网关。
        |## 安全约束
        |禁止静默删除、禁止扩大确认范围、每个确认令牌只能使用一次。
    """.trimMargin()
}
