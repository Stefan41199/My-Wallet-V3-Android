package com.blockchain.coincore.selfcustody

import com.blockchain.coincore.IdentityAddressResolver
import com.blockchain.coincore.ReceiveAddress
import com.blockchain.coincore.SingleAccountList
import com.blockchain.coincore.impl.CryptoAssetBase
import com.blockchain.core.chains.dynamicselfcustody.NonCustodialService
import com.blockchain.featureflag.FeatureFlag
import com.blockchain.wallet.DefaultLabels
import info.blockchain.balance.AssetInfo
import info.blockchain.wallet.dynamicselfcustody.CoinConfiguration
import info.blockchain.wallet.payload.data.Derivation
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.Singles
import piuk.blockchain.androidcore.data.payload.PayloadDataManager
import piuk.blockchain.androidcore.utils.helperfunctions.unsafeLazy

internal class StxAsset(
    override val assetInfo: AssetInfo,
    private val payloadManager: PayloadDataManager,
    private val addressResolver: IdentityAddressResolver,
    private val addressValidation: String? = null,
    private val selfCustodyService: NonCustodialService,
    private val stxForAllFeatureFlag: FeatureFlag,
    private val stxForAirdropFeatureFlag: FeatureFlag
) : CryptoAssetBase() {

    override fun loadNonCustodialAccounts(labels: DefaultLabels): Single<SingleAccountList> =
        Singles.zip(
            stxForAllFeatureFlag.enabled,
            stxForAirdropFeatureFlag.enabled,
            identity.hasReceivedStxAirdrop()
        )
            .map { (isEnabledForAll, isEnabledForAirdropUsers, hasReceivedAirdrop) ->
                when {
                    // TODO(dtverdota): AND-6168 STX | Balances
                    isEnabledForAll || (isEnabledForAirdropUsers && hasReceivedAirdrop) ->
                        listOf(
                            DynamicNonCustodialAccount(
                                payloadManager,
                                assetInfo,
                                coinConfiguration,
                                addressResolver,
                                selfCustodyService,
                                exchangeRates,
                                labels.getDefaultNonCustodialWalletLabel()
                            )
                        )
                    else -> emptyList()
                }
            }

    private val addressRegex: Regex? by unsafeLazy {
        addressValidation?.toRegex()
    }

    override fun parseAddress(address: String, label: String?, isDomainAddress: Boolean): Maybe<ReceiveAddress> =
        addressRegex?.let {
            if (address.matches(it)) {
                Maybe.just(
                    DynamicNonCustodialAddress(
                        address = address,
                        asset = assetInfo,
                        isDomain = isDomainAddress
                    )
                )
            } else {
                Maybe.empty()
            }
        } ?: Maybe.empty()

    companion object {
        private const val STX_COIN_TYPE = 5757
        private val coinConfiguration = CoinConfiguration(
            coinType = STX_COIN_TYPE,
            purpose = Derivation.LEGACY_PURPOSE
        )
    }
}
