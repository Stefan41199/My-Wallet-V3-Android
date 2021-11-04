package com.blockchain.api.assetdiscovery

import com.blockchain.api.assetdiscovery.data.DynamicCurrencyList
import com.blockchain.network.interceptor.Cacheable
import com.blockchain.network.interceptor.DoNotLogResponseBody
import io.reactivex.rxjava3.core.Single
import retrofit2.http.GET

internal interface AssetDiscoveryApiInterface {

    @Cacheable(maxAge = Cacheable.MAX_AGE_THREE_DAYS)
    @DoNotLogResponseBody
    @GET("assets/currencies/coin")
    fun getCurrencies(): Single<DynamicCurrencyList>

    @Cacheable(maxAge = Cacheable.MAX_AGE_THREE_DAYS)
    @DoNotLogResponseBody
    @GET("assets/currencies/fiat")
    fun getFiatCurrencies(): Single<DynamicCurrencyList>

    @Cacheable(maxAge = Cacheable.MAX_AGE_THREE_DAYS)
    @DoNotLogResponseBody
    @GET("assets/currencies/erc20")
    fun getErc20Currencies(): Single<DynamicCurrencyList>

    @Cacheable(maxAge = Cacheable.MAX_AGE_THREE_DAYS)
    @DoNotLogResponseBody
    @GET("assets/currencies/custodial")
    fun getCustodialCurrencies(): Single<DynamicCurrencyList>
}
