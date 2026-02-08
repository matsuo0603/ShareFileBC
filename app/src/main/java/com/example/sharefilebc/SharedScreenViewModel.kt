package com.example.sharefilebc

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharefilebc.data.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * SharedScreen の deep link 受信処理を "画面の Composition スコープ" から切り離すための ViewModel。
 *
 * これにより、画面の再Compose / タブ切替 / 画面遷移で LaunchedEffect がキャンセルされても
 * processReceivedShare が途中で中断されにくくなる（LeftCompositionCancellationException 対策）。
 */
class SharedScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val dlTag = "DL_DEBUG"

    /**
     * UUID|txids の処理キー。
     * - メモリ上の二重処理防止
     * - WorkManager/DB ガードと併用
     */
    private val processedKeys = ConcurrentHashMap<String, Boolean>()

    fun triggerProcessReceivedShareIfNeeded(
        processKey: String,
        selectedTabLabel: String,
        uuid: String?,
        txids: List<String>,
        senderPublicKey: String?,
        refundAddress: String?,
        threshold: ULong,
        colorId: String
    ) {
        if (processKey.isBlank()) {
            Log.d(dlTag, "[SharedScreenVM] skip: empty processKey")
            return
        }
        if (selectedTabLabel != "受信") {
            Log.d(dlTag, "[SharedScreenVM] skip: selectedTab=$selectedTabLabel processKey=$processKey")
            return
        }
        if (uuid.isNullOrBlank() || txids.isEmpty() || senderPublicKey.isNullOrBlank()) {
            Log.d(
                dlTag,
                "[SharedScreenVM] missing params uuid=$uuid txids=${txids.size} senderKeyEmpty=${senderPublicKey.isNullOrBlank()} threshold=$threshold"
            )
            return
        }

        // ① メモリガード
        if (processedKeys[processKey] == true) {
            Log.d(dlTag, "[SharedScreenVM] skip by memory guard key=$processKey")
            return
        }

        // 受信処理は ViewModel の viewModelScope で実行（画面の composition scope から独立）
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // ② DBガード（received_files OR refund_tasks）
                val alreadyProcessedInDb = withContext(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(getApplication())
                    val hasReceived = db.receivedFileDao().findByShareId(uuid) != null
                    val hasRefundTask = db.refundTaskDao().findByShareId(uuid) != null
                    hasReceived || hasRefundTask
                }
                if (alreadyProcessedInDb) {
                    processedKeys[processKey] = true
                    Log.d(dlTag, "[SharedScreenVM] skip by DB guard key=$processKey")
                    return@launch
                }

                Log.d(dlTag, "[SharedScreenVM] processReceivedShare start key=$processKey")

                val result = ShareProcessor.processReceivedShare(
                    context = getApplication(),
                    uuid = uuid,
                    txids = txids,
                    senderPublicKey = senderPublicKey,
                    refundAddress = refundAddress,
                    threshold = threshold,
                    colorId = colorId
                )

                processedKeys[processKey] = true
                Log.d(dlTag, "[SharedScreenVM] processReceivedShare end key=$processKey result=$result")

            } catch (ce: CancellationException) {
                // キャンセルはエラー扱いにしない（OS が落とした／スコープが閉じた等）
                Log.d(dlTag, "[SharedScreenVM] processReceivedShare cancelled key=$processKey")
                throw ce
            } catch (t: Throwable) {
                Log.e(dlTag, "[SharedScreenVM] processReceivedShare failed key=$processKey", t)
            }
        }
    }
}
