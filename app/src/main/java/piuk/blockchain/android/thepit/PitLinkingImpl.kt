package piuk.blockchain.android.thepit

import com.blockchain.core.chains.bitcoincash.BchDataManager
import com.blockchain.nabu.NabuToken
import com.blockchain.nabu.datamanagers.NabuDataManager
import com.blockchain.nabu.models.responses.nabu.NabuUser
import com.blockchain.sunriver.XlmDataManager
import info.blockchain.balance.CryptoCurrency
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.Singles
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import java.lang.IllegalStateException
import piuk.blockchain.androidcore.data.ethereum.EthDataManager
import piuk.blockchain.androidcore.data.payload.PayloadDataManager
import thepit.PitLinking
import thepit.PitLinkingState
import timber.log.Timber

class PitLinkingImpl(
    private val nabu: NabuDataManager,
    private val nabuToken: NabuToken,
    private val payloadDataManager: PayloadDataManager,
    private val ethDataManager: EthDataManager,
    private val bchDataManager: BchDataManager,
    private val xlmDataManager: XlmDataManager
) : PitLinking {

    private val disposables = CompositeDisposable()

    private fun publish(state: PitLinkingState) {
        internalState.onNext(state)
    }

    private val internalState = BehaviorSubject.create<PitLinkingState>()
    private val refreshEvents = PublishSubject.create<Unit>()

    override val state: Observable<PitLinkingState> = internalState.doOnSubscribe { onNewSubscription() }

    init {
        disposables += refreshEvents.switchMapSingle {
            nabuToken.fetchNabuToken().flatMap { token -> nabu.getUser(token) }
        }.subscribeOn(Schedulers.computation())
            .map { it.toLinkingState() }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onNext = { publish(it) },
                onComplete = { },
                onError = { }
            )
    }

    private fun NabuUser.toLinkingState(): PitLinkingState {
        return PitLinkingState(
            isLinked = exchangeEnabled,
            emailVerified = emailVerified,
            email = email
        )
    }

    private fun onNewSubscription() {
        // Re-fetch the user state
        refreshEvents.onNext(Unit)
    }

    // Temporary helper method, for all the MVP clients:
    override fun isPitLinked(): Single<Boolean> =
        state.flatMapSingle { state -> Single.just(state.isLinked) }
            .firstOrError()
            .onErrorResumeNext { Single.just(false) }

    override fun sendWalletAddressToThePit() {
        disposables += Singles.zip(
            nabuToken.fetchNabuToken(),
            fetchAddressMap()
        )
            .subscribeOn(Schedulers.computation())
            .flatMapCompletable { nabu.shareWalletAddressesWithThePit(it.first, it.second) }
            .subscribeBy(onError = { Timber.e("Unable to send local addresses to the pit: $it") })
    }

    private fun fetchAddressMap(): Single<HashMap<String, String>> =
        Single.merge(
            listOf(
                getBtcReceiveAddress(),
                getBchReceiveAddress(),
                getEthReceiveAddress(),
                getXlmReceiveAddress()
            )
        )
            .filter { it.isNotEmpty() }
            .collect({ HashMap() }, { m, i -> m[i.first] = i.second })

    private fun Pair<String, String>.isNotEmpty() = first.isNotEmpty() && second.isNotEmpty()

    private fun getBtcReceiveAddress(): Single<Pair<String, String>> {
        return Single.fromCallable {
            payloadDataManager.getReceiveAddressAtPosition(
                payloadDataManager.defaultAccount,
                1
            )
        }
            .map { Pair(CryptoCurrency.BTC.networkTicker, it ?: throw IllegalStateException("address is null")) }
            .onErrorReturn { Pair("", "") }
    }

    private fun getBchReceiveAddress(): Single<Pair<String, String>> {
        val pos = bchDataManager.getDefaultAccountPosition()
        return bchDataManager.getNextCashReceiveAddress(pos)
            .map { Pair(CryptoCurrency.BCH.networkTicker, it) }
            .singleOrError()
            .onErrorReturn { Pair("", "") }
    }

    private fun getEthReceiveAddress(): Single<Pair<String, String>> =
        Single.fromCallable { ethDataManager.accountAddress }
            .map { Pair(CryptoCurrency.ETHER.networkTicker, it.orEmpty()) }
            .onErrorReturn { Pair("", "") }

    private fun getXlmReceiveAddress(): Single<Pair<String, String>> =
        xlmDataManager.defaultAccount()
            .map { Pair(CryptoCurrency.XLM.networkTicker, it.accountId) }
            .onErrorReturn { Pair("", "") }
}
