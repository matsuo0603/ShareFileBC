package com.example.sharefilebc

/**
 * 送金着金の正当性検証ロジック（純粋関数）。
 *
 * - 送金量合計 < 閾値: payable=true, 送信者を拒否リストへ, ダウンロード不可
 * - 送金量合計 >= 閾値 かつ 拒否リスト未登録: payable=false, ダウンロード許可
 * - 拒否リスト登録済み: payable=true, ダウンロード不可（保護しない）
 */
fun evaluateSharePayment(
    totalAmount: ULong,
    threshold: ULong,
    isSenderBlocked: Boolean
): ShareVerificationDecision {
    return when {
        totalAmount < threshold -> ShareVerificationDecision.Rejected(
            reason = ShareVerificationRejectionReason.BELOW_THRESHOLD,
            markPayable = true,
            addToBlockedList = true,
            allowDownload = false
        )

        isSenderBlocked -> ShareVerificationDecision.Rejected(
            reason = ShareVerificationRejectionReason.SENDER_BLOCKED,
            markPayable = true,
            addToBlockedList = false,
            allowDownload = false
        )

        else -> ShareVerificationDecision.Accepted(
            markPayable = false,
            allowDownload = true
        )
    }
}

sealed class ShareVerificationDecision {
    data class Accepted(
        val markPayable: Boolean,
        val allowDownload: Boolean
    ) : ShareVerificationDecision()

    data class Rejected(
        val reason: ShareVerificationRejectionReason,
        val markPayable: Boolean,
        val addToBlockedList: Boolean,
        val allowDownload: Boolean
    ) : ShareVerificationDecision()
}

enum class ShareVerificationRejectionReason {
    BELOW_THRESHOLD,
    SENDER_BLOCKED
}