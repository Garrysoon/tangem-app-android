package com.tangem.tap.domain.userWalletList.repository

import com.tangem.datasource.local.db.TangemDatabase
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.utils.logging.TangemLogger

/**
 * Helper to clean up Room DB data when a wallet is deleted.
 */
internal class WalletRoomDbCleanupHelper(
    private val database: TangemDatabase,
) {

    suspend fun cleanup(userWalletId: UserWalletId) {
        try {
            TangemLogger.i("Cleaning Room DB for wallet: ${userWalletId.stringValue}")
            database.userWalletDao().deleteById(userWalletId.stringValue)
            database.cryptoCurrenciesAccountDao().deleteByWalletId(userWalletId)
            database.cryptoCurrencyDao().deleteByWalletId(userWalletId)
            TangemLogger.i("Room DB cleanup complete for wallet: ${userWalletId.stringValue}")
        } catch (e: Exception) {
            TangemLogger.e("Room DB cleanup failed for wallet ${userWalletId.stringValue}: $e")
        }
    }
}
