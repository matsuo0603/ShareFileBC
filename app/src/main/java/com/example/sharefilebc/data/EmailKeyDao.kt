package com.example.sharefilebc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EmailKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: EmailKeyEntity)

    @Query("SELECT * FROM email_keys")
    fun getAll(): Flow<List<EmailKeyEntity>>

    @Query("SELECT * FROM email_keys WHERE email = :email LIMIT 1")
    suspend fun findByEmail(email: String): EmailKeyEntity?

    /**
     * sender= に入ってくる公開鍵が derived/trust のどちらか分からないケースがあるため、逆引き用に追加。
     */
    @Query("SELECT * FROM email_keys WHERE derivedPublicKey = :publicKey LIMIT 1")
    suspend fun findByDerivedPublicKey(publicKey: String): EmailKeyEntity?

    @Update
    suspend fun update(key: EmailKeyEntity)

    /**
     * ShareProcessor.registerFraudulentSender で使用
     * TrustLayer公開鍵からメールアドレスを逆引き
     */
    @Query("SELECT * FROM email_keys WHERE trustLayerPublicKey = :publicKey LIMIT 1")
    suspend fun findByTrustLayerPublicKey(publicKey: String): EmailKeyEntity?

    /**
     * sender= がメールアドレスを含まないため、公開鍵だけから EmailKeyEntity を探す必要がある。
     */
    @Query("SELECT * FROM email_keys WHERE trustLayerPublicKey = :publicKey OR derivedPublicKey = :publicKey LIMIT 1")
    suspend fun findByAnyPublicKey(publicKey: String): EmailKeyEntity?
}