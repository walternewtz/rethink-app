/*
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.animation.Rotate3dAnimation
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.BottomSheetOrbotBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.isAtleastQ
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * One touch Orbot Integration. Bottom sheet dialog fragment shows UI that enables One touch
 * Integration from the settings page.
 */
class OrbotBottomSheetFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetOrbotBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b
        get() = _binding!!

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val orbotHelper by inject<OrbotHelper>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetOrbotBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun getTheme(): Int =
        Themes.getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        setupClickListeners()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun initView() {
        io {
            val isOrbotDns = appConfig.isOrbotDns()
            uiCtx { updateUi(isOrbotDns) }
        }
        handleHttpUI()
    }

    private fun handleHttpUI() {
        // http proxy is only supported on Android Q and above
        if (!isAtleastQ()) {
            b.bsOrbotHttpRl.visibility = View.GONE
            b.bsOrbotBothRl.visibility = View.GONE
        }
    }

    private fun setupClickListeners() {

        b.bsOrbotApp.setOnClickListener { orbotHelper.openOrbotApp() }

        b.bsOrbotRadioNone.setOnCheckedChangeListener(null)
        b.bsOrbotRadioNone.setOnClickListener {
            if (b.bsOrbotRadioNone.isChecked) {
                handleOrbotStop()
            }
        }

        b.bsOrbotNoneRl.setOnClickListener {
            if (!b.bsOrbotRadioNone.isChecked) {
                handleOrbotStop()
            }
        }

        b.bsOrbotRadioSocks5.setOnCheckedChangeListener(null)
        b.bsOrbotRadioSocks5.setOnClickListener {
            if (!b.bsOrbotRadioSocks5.isSelected) {
                persistentState.orbotConnectionStatus.postValue(true)
                enableSocks5Orbot()
            }
        }

        b.bsSocks5OrbotRl.setOnClickListener {
            if (!b.bsOrbotRadioSocks5.isChecked) {
                b.bsOrbotRadioSocks5.isChecked = true
                persistentState.orbotConnectionStatus.postValue(true)
                enableSocks5Orbot()
            }
        }

        b.bsOrbotRadioHttp.setOnCheckedChangeListener(null)
        b.bsOrbotRadioHttp.setOnClickListener {
            if (!b.bsOrbotRadioHttp.isSelected) {
                persistentState.orbotConnectionStatus.postValue(true)
                enableHttpOrbot()
            }
        }

        b.bsOrbotHttpRl.setOnClickListener {
            if (!b.bsOrbotRadioHttp.isChecked) {
                persistentState.orbotConnectionStatus.postValue(true)
                b.bsOrbotRadioHttp.isChecked = true
                enableHttpOrbot()
            }
        }

        b.bsOrbotRadioBoth.setOnCheckedChangeListener(null)
        b.bsOrbotRadioBoth.setOnClickListener {
            if (!b.bsOrbotRadioBoth.isSelected) {
                persistentState.orbotConnectionStatus.postValue(true)
                enableSocks5HttpOrbot()
            }
        }

        b.bsOrbotBothRl.setOnClickListener {
            if (!b.bsOrbotRadioBoth.isChecked) {
                persistentState.orbotConnectionStatus.postValue(true)
                b.bsOrbotRadioBoth.isChecked = true
                enableSocks5HttpOrbot()
            }
        }

        b.orbotInfoIcon.setOnClickListener { showDialogForInfo() }

        // Livedata value which will have the data whether the Orbot connection is initiated
        // If initiated, show loading animation.
        // else - allow user to select Orbot options
        persistentState.orbotConnectionStatus.observe(viewLifecycleOwner) {
            if (it) {
                b.orbotIcon.setImageResource(R.drawable.orbot_disabled)
                enableLoading()
            } else {
                disableLoading()
                io {
                    val isOrbotDns = appConfig.isOrbotDns()
                    uiCtx { updateUi(isOrbotDns) }
                }
            }
        }
    }

    private fun handleOrbotStop() {
        stopOrbot()
        io {
            val isOrbotDns = appConfig.isOrbotDns()
            uiCtx { showStopOrbotDialog(isOrbotDns) }
        }
    }

    private fun updateUi(isOrbotDns: Boolean) {
        when (OrbotHelper.selectedProxyType) {
            AppConfig.ProxyType.SOCKS5.name -> {
                b.bsOrbotRadioSocks5.isChecked = true
                b.orbotIcon.setImageResource(R.drawable.orbot_enabled)
                if (isOrbotDns) {
                    b.orbotStatus.text =
                        getString(
                            R.string.orbot_bs_status_1,
                            getString(R.string.orbot_status_arg_3)
                        )
                } else {
                    b.orbotStatus.text =
                        getString(
                            R.string.orbot_bs_status_1,
                            getString(R.string.orbot_status_arg_2)
                        )
                }
            }
            AppConfig.ProxyType.HTTP.name -> {
                b.bsOrbotRadioHttp.isChecked = true
                b.orbotIcon.setImageResource(R.drawable.orbot_enabled)
                b.orbotStatus.text = getString(R.string.orbot_bs_status_2)
            }
            AppConfig.ProxyType.HTTP_SOCKS5.name -> {
                b.bsOrbotRadioBoth.isChecked = true
                b.orbotIcon.setImageResource(R.drawable.orbot_enabled)
                b.orbotStatus.text = getString(R.string.orbot_bs_status_3)
                if (isOrbotDns) {
                    b.orbotStatus.text =
                        getString(
                            R.string.orbot_bs_status_3,
                            getString(R.string.orbot_status_arg_3)
                        )
                } else {
                    b.orbotStatus.text =
                        getString(
                            R.string.orbot_bs_status_3,
                            getString(R.string.orbot_status_arg_2)
                        )
                }
            }
            AppConfig.ProxyType.NONE.name -> {
                updateOrbotNone()
            }
            else -> {
                updateOrbotNone()
            }
        }
    }

    private fun updateOrbotNone() {
        b.bsOrbotRadioNone.isChecked = true
        b.bsOrbotRadioSocks5.isChecked = false
        b.bsOrbotRadioHttp.isChecked = false
        b.bsOrbotRadioBoth.isChecked = false
        b.orbotIcon.setImageResource(R.drawable.orbot_disabled)
        b.orbotStatus.text = getString(R.string.orbot_bs_status_4)
    }

    /**
     * Disables the UI from selecting any other mode until the Orbot connection status is
     * obtained/time out for the Orbot.
     */
    private fun enableLoading() {
        b.bsSocks5OrbotRl.isClickable = false
        b.bsOrbotBothRl.isClickable = false
        b.bsOrbotHttpRl.isClickable = false
        b.bsOrbotNoneRl.isClickable = false
        b.bsOrbotRadioGroup.isClickable = false
        b.bsOrbotRadioSocks5.isClickable = false
        b.bsOrbotRadioHttp.isClickable = false
        b.bsOrbotRadioBoth.isClickable = false
        b.bsOrbotRadioNone.isClickable = false
        b.orbotStatus.text = getString(R.string.orbot_bs_status_trying_connect)
        animateOrbotIcon()
    }

    /** Circular animation for the Orbot Icon. */
    private fun animateOrbotIcon() {
        // In some cases, the width of orbotIcon is 0 - For both width and measuredWidth.
        // Convert the width(40dp) to pixel and add to animation parameter in case of 0
        var width = b.orbotIcon.measuredWidth
        if (width == 0) {
            width = getCalculatedWidth()
        }
        val rotation = Rotate3dAnimation(0.0f, 360.0f * 4f, width / 2f, width / 2f, 20f, true)
        rotation.fillAfter = true
        rotation.interpolator = AccelerateInterpolator()
        rotation.duration = 2.toLong() * 1000
        rotation.repeatCount = Animation.INFINITE
        b.orbotIcon.startAnimation(rotation)
    }

    // ref: https://stackoverflow.com/questions/4605527/converting-pixels-to-dp
    // Calculate the width of the icon manually.
    // Invoke this method when the width of the Orbot icon is returned as 0 by viewBinder.
    private fun getCalculatedWidth(): Int {
        val dip = 40f
        val r: Resources = resources
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip, r.displayMetrics).toInt()
    }

    private fun disableLoading() {
        b.bsSocks5OrbotRl.isClickable = true
        b.bsOrbotBothRl.isClickable = true
        b.bsOrbotHttpRl.isClickable = true
        b.bsOrbotNoneRl.isClickable = true
        b.bsOrbotRadioGroup.isClickable = true
        b.bsOrbotRadioSocks5.isClickable = true
        b.bsOrbotRadioHttp.isClickable = true
        b.bsOrbotRadioBoth.isClickable = true
        b.bsOrbotRadioNone.isClickable = true
        b.orbotIcon.clearAnimation()
    }

    /** Stop the Orbot - Calls the Orbot helper to initiate the stop orbot call. */
    private fun stopOrbot() {
        appConfig.removeAllProxies()
        b.bsOrbotRadioSocks5.isChecked = false
        b.bsOrbotRadioHttp.isChecked = false
        b.bsOrbotRadioBoth.isChecked = false
        b.bsOrbotRadioNone.isChecked = true
        b.orbotIcon.setImageResource(R.drawable.orbot_disabled)
        orbotHelper.stopOrbot(isInteractive = true)
    }

    /** Enable - Orbot with SOCKS5 mode */
    private fun enableSocks5Orbot() {
        b.bsOrbotRadioNone.isChecked = false
        b.bsOrbotRadioHttp.isChecked = false
        b.bsOrbotRadioBoth.isChecked = false
        startOrbot(AppConfig.ProxyType.SOCKS5.name)
    }

    /** Enable - Orbot with HTTP mode */
    private fun enableHttpOrbot() {
        b.bsOrbotRadioNone.isChecked = false
        b.bsOrbotRadioSocks5.isChecked = false
        b.bsOrbotRadioBoth.isChecked = false
        startOrbot(AppConfig.ProxyType.HTTP.name)
    }

    /** Enable - Orbot with SOCKS5 + HTTP mode */
    private fun enableSocks5HttpOrbot() {
        b.bsOrbotRadioNone.isChecked = false
        b.bsOrbotRadioSocks5.isChecked = false
        b.bsOrbotRadioHttp.isChecked = false
        startOrbot(AppConfig.ProxyType.HTTP_SOCKS5.name)
    }

    /** Start the Orbot(OrbotHelper) - Intent action. */
    private fun startOrbot(type: String) {
        if (!FirewallManager.isOrbotInstalled()) {
            return
        }

        if (VpnController.hasTunnel()) {
            go { uiCtx { orbotHelper.startOrbot(type) } }
        } else {
            Utilities.showToastUiCentered(
                requireContext(),
                getString(R.string.settings_socks5_vpn_disabled_error),
                Toast.LENGTH_LONG
            )
        }
    }

    private fun showStopOrbotDialog(isOrbotDns: Boolean) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(getString(R.string.orbot_stop_dialog_title))

        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.lbl_dismiss)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        builder.setNegativeButton(getString(R.string.orbot_stop_dialog_negative)) {
            dialogInterface: DialogInterface,
            _: Int ->
            dialogInterface.dismiss()
            orbotHelper.openOrbotApp()
        }
        if (isOrbotDns) {
            builder.setMessage(
                getString(
                    R.string.orbot_stop_dialog_message_combo,
                    getString(R.string.orbot_stop_dialog_message),
                    getString(R.string.orbot_stop_dialog_dns_message)
                )
            )
            builder.setNeutralButton(getString(R.string.orbot_stop_dialog_neutral)) {
                dialogInterface,
                _ ->
                dialogInterface.dismiss()
                gotoDnsConfigureScreen()
            }
        } else {
            builder.setMessage(getString(R.string.orbot_stop_dialog_message))
        }
        builder.create().show()
    }

    private fun gotoDnsConfigureScreen() {
        this.dismiss()
        val intent = Intent(requireContext(), DnsDetailActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        intent.putExtra(
            Constants.VIEW_PAGER_SCREEN_TO_LOAD,
            DnsDetailActivity.Tabs.CONFIGURE.screen
        )
        startActivity(intent)
    }

    private fun showDialogForInfo() {
        val dialogBinding = DialogInfoRulesLayoutBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(dialogBinding.root)
        val okBtn = dialogBinding.infoRulesDialogCancelImg
        val descText = dialogBinding.infoRulesDialogRulesDesc
        val titleText = dialogBinding.infoRulesDialogRulesTitle

        var text = getString(R.string.orbot_explanation)
        text = text.replace("\n", "<br /><br />")
        val styledText = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
        descText.text = styledText
        titleText.text = getString(R.string.orbot_title)
        titleText.setCompoundDrawablesRelative(null, null, null, null)

        okBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun go(f: suspend () -> Unit) {
        lifecycleScope.launch { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }
}
