package piuk.blockchain.android.ui.interest.tbm.domain.usecase

import com.blockchain.coincore.AccountGroup
import com.blockchain.coincore.AssetFilter
import info.blockchain.balance.AssetInfo
import piuk.blockchain.android.ui.interest.tbm.domain.repository.AssetInterestRepository

// todo move to coincore module
class GetAccountGroupUseCase(private val repository: AssetInterestRepository) {
    suspend operator fun invoke(
        cryptoCurrency: AssetInfo,
        filter: AssetFilter = AssetFilter.All
    ): Result<AccountGroup> = repository.getAccountGroup(cryptoCurrency = cryptoCurrency, filter = filter)
}