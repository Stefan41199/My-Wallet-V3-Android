package piuk.blockchain.android.coincore.loader

import info.blockchain.balance.AssetCatalogue
import info.blockchain.balance.AssetInfo
import info.blockchain.balance.isCustodial
import io.reactivex.rxjava3.core.Single
import piuk.blockchain.androidcore.utils.extensions.thenSingle
import java.util.Locale

internal class AssetCatalogueImpl(
    private val featureConfig: AssetRemoteFeatureLookup
) : AssetCatalogue {

    private lateinit var fullAssetLookup: Map<String, AssetInfo>

    fun initialise(fixedAssets: Set<AssetInfo>): Single<Set<AssetInfo>> {
        val nonDynamicAssets = fixedAssets + staticAssets
        return featureConfig.init(nonDynamicAssets)
            .thenSingle {
                initDynamicAssets()
            }.doOnSuccess { enabledAssets ->
                val allEnabledAssets = nonDynamicAssets + enabledAssets
                fullAssetLookup = allEnabledAssets.associateBy { it.ticker.toUpperCase(Locale.ROOT) }
            }.map {
                it + staticAssets
            }
    }

    private fun initDynamicAssets(): Single<Set<AssetInfo>> =
        Single.fromCallable {
            dynamicAssets.filterNot {
                featureConfig.featuresFor(it).isEmpty()
            }.toSet()
        }

    // Brute force impl for now, but this will operate from a downloaded cache ultimately
    override fun fromNetworkTicker(symbol: String): AssetInfo? =
        fullAssetLookup[symbol.uppercase()]

    override fun fromNetworkTickerWithL2Id(
        symbol: String,
        l2chain: AssetInfo,
        l2Id: String
    ): AssetInfo? =
        fromNetworkTicker(symbol)?.let { found ->
            found.takeIf {
                it.l2chain == l2chain &&
                it.l2identifier?.compareTo(l2Id, ignoreCase = true) == 0
            }
        }

    override fun isFiatTicker(symbol: String): Boolean =
        supportedFiatAssets.contains(symbol)

    override val supportedCryptoAssets: List<AssetInfo> by lazy {
        fullAssetLookup.values.toList()
    }

    override val supportedCustodialAssets: List<AssetInfo> by lazy {
        fullAssetLookup.values.filter { it.isCustodial }
    }

    // TEMP: This will come from the /fiat BE supported assets call
    override val supportedFiatAssets: List<String> = listOf("EUR", "USD", "GBP")

    override fun supportedL2Assets(chain: AssetInfo): List<AssetInfo> =
        supportedCryptoAssets.filter { it.l2chain == chain }

    companion object {
        private val staticAssets: Set<AssetInfo> =
            setOf(
                WDGLD,
                PAX,
                USDT,
                AAVE,
                YFI
            )

        private val dynamicAssets: Set<AssetInfo> =
            setOf(
                ALGO,
                DOT,
                DOGE,
                CLOUT,
                LTC,
                ETC,
                XTZ,
                STX,
                MOB,
                THETA,
                NEAR,
                EOS,
                OGN,
                ENJ,
                COMP,
                LINK,
                TBTC,
                WBTC,
                SNX,
                SUSHI,
                ZRX,
                USDC,
                UNI,
                DAI,
                BAT
            )
    }
}