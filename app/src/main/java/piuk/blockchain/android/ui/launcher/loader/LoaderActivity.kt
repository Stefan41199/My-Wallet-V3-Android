package piuk.blockchain.android.ui.launcher.loader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.Toolbar
import com.blockchain.koin.scopedInject
import com.blockchain.notifications.analytics.KYCAnalyticsEvents
import com.blockchain.notifications.analytics.LaunchOrigin
import piuk.blockchain.android.R
import piuk.blockchain.android.databinding.ActivityLoaderBinding
import piuk.blockchain.android.databinding.ToolbarGeneralBinding
import piuk.blockchain.android.ui.auth.PinEntryActivity
import piuk.blockchain.android.ui.base.mvi.MviActivity
import piuk.blockchain.android.ui.customviews.ToastCustom
import piuk.blockchain.android.ui.customviews.toast
import piuk.blockchain.android.ui.home.MainActivity
import piuk.blockchain.android.ui.kyc.email.entry.EmailEntryHost
import piuk.blockchain.android.ui.kyc.email.entry.KycEmailEntryFragment
import piuk.blockchain.android.ui.launcher.LauncherActivity
import piuk.blockchain.android.util.AppUtil
import piuk.blockchain.android.util.ViewUtils
import piuk.blockchain.android.util.gone
import piuk.blockchain.android.util.visible
import piuk.blockchain.android.util.visibleIf

class LoaderActivity : MviActivity<LoaderModel, LoaderIntents, LoaderState, ActivityLoaderBinding>(), EmailEntryHost {

    override val model: LoaderModel by scopedInject()

    override val alwaysDisableScreenshots: Boolean = true

    override fun initBinding(): ActivityLoaderBinding = ActivityLoaderBinding.inflate(layoutInflater)

    private val toolbar: Toolbar by lazy {
        ToolbarGeneralBinding.bind(binding.root).toolbarGeneral
    }

    private var state: LoaderState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setSupportActionBar(toolbar)
        toolbar.gone()

        val extras = intent?.extras
        val isPinValidated = extras?.getBoolean(INTENT_EXTRA_VERIFIED, false) ?: false
        val isAfterWalletCreation = extras?.getBoolean(AppUtil.INTENT_EXTRA_IS_AFTER_WALLET_CREATION, false) == true

        model.process(LoaderIntents.CheckIsLoggedIn(isPinValidated, isAfterWalletCreation))
    }

    override fun render(newState: LoaderState) {
        when (val loaderStep = newState.nextLoaderStep) {
            is LoaderStep.Main -> {
                onStartMainActivity(loaderStep.data, loaderStep.launchBuySellIntro)
            }
            is LoaderStep.Launcher -> startSingleActivity(LauncherActivity::class.java)
            is LoaderStep.EmailVerification -> launchEmailVerification()
            null -> {
            }
        }

        updateUi(newState)

        state = newState
    }

    private fun updateUi(newState: LoaderState) {
        when (newState.nextProgressStep) {
            ProgressStep.START -> {
                updateProgressVisibility(true)
            }
            ProgressStep.LOADING_PRICES -> {
                updateProgressVisibility(true)
                updateProgressText(R.string.loading_prices)
            }
            ProgressStep.SYNCING_ACCOUNT -> {
                updateProgressVisibility(true)
                updateProgressText(R.string.syncing_account)
            }
            ProgressStep.DECRYPTING_WALLET -> {
                updateProgressVisibility(true)
                updateProgressText(R.string.decrypting_wallet)
            }
            ProgressStep.FINISH -> {
                updateProgressVisibility(false)
            }
        }

        if (newState.shouldShowSecondPasswordDialog) {
            showSecondPasswordDialog()
        }

        if (newState.shouldShowMetadataNodeFailure) {
            showMetadataNodeFailure()
        }

        if (newState.toastType != null) {
            when (newState.toastType) {
                ToastType.INVALID_PASSWORD -> showToast(R.string.invalid_password, ToastCustom.TYPE_ERROR)
                ToastType.UNEXPECTED_ERROR -> showToast(R.string.unexpected_error, ToastCustom.TYPE_ERROR)
            }
        }
    }

    override fun onEmailEntryFragmentShown() {
        with(toolbar) {
            setupToolbar(this, R.string.security_check)
            navigationIcon = null
            visible()
        }
    }

    override fun onEmailVerified() {
        model.process(LoaderIntents.OnEmailVerificationFinished)
    }

    override fun onEmailVerificationSkipped() {
        model.process(LoaderIntents.OnEmailVerificationFinished)
        analytics.logEvent(KYCAnalyticsEvents.EmailVeriffSkipped(LaunchOrigin.SIGN_UP))
    }

    private fun onRequestPin() {
        startSingleActivity(PinEntryActivity::class.java)
    }

    private fun onStartMainActivity(mainData: String?, launchBuySellIntro: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mainData?.let {
                data = Uri.parse(it)
            }
            putExtra(MainActivity.START_BUY_SELL_INTRO_KEY, launchBuySellIntro)
        }
        startActivity(intent)
    }

    private fun launchEmailVerification() {
        window.statusBarColor = getColor(R.color.primary_blue_dark)
        binding.progress.visibility = View.GONE
        binding.contentFrame.visibility = View.VISIBLE
        analytics.logEvent(KYCAnalyticsEvents.EmailVeriffRequested(LaunchOrigin.SIGN_UP))
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_frame, KycEmailEntryFragment(), KycEmailEntryFragment::class.simpleName)
            .commitAllowingStateLoss()
    }

    private fun showToast(message: Int, toastType: String) {
        toast(message, toastType)
    }

    private fun showMetadataNodeFailure() {
        if (!isFinishing) {
            AlertDialog.Builder(this, R.style.AlertDialogStyle)
                .setTitle(R.string.app_name)
                .setMessage(R.string.metadata_load_failure)
                .setPositiveButton(R.string.retry) { _, _ -> onRequestPin() }
                .setNegativeButton(R.string.exit) { _, _ -> finish() }
                .setCancelable(false)
                .setOnDismissListener {
                    model.process(LoaderIntents.HideMetadataNodeFailure)
                }
                .create()
                .show()
        }
    }

    private fun showSecondPasswordDialog() {
        val editText = AppCompatEditText(this)
        editText.setHint(R.string.password)
        editText.inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        val frameLayout = ViewUtils.getAlertDialogPaddedView(this, editText)

        AlertDialog.Builder(this, R.style.AlertDialogStyle)
            .setTitle(R.string.second_password_dlg_title)
            .setMessage(R.string.eth_second_password_prompt)
            .setView(frameLayout)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ViewUtils.hideKeyboard(this)
                model.process(LoaderIntents.DecryptAndSetupMetadata(editText.text.toString()))
            }
            .setOnDismissListener {
                model.process(LoaderIntents.HideSecondPasswordDialog)
            }
            .create()
            .show()
    }

    private fun updateProgressVisibility(show: Boolean) {
        binding.progress.visibleIf { show }
    }

    private fun updateProgressText(text: Int) {
        binding.progress.text = getString(text)
    }

    private fun startSingleActivity(clazz: Class<*>) {
        val intent = Intent(this, clazz).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    companion object {
        private const val INTENT_EXTRA_VERIFIED = "verified"
    }
}