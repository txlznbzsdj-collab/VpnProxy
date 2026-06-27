package com.vpnproxy.app

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView

class ServerBottomSheet(
    private val onServerSelected: (name: String, host: String) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_VpnProxy)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_servers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupServers(view)
        animateEntry(view)
    }

    private fun animateEntry(view: View) {
        val container = view.findViewById<ConstraintLayout>(R.id.sheetContainer)
        val items = container?.let { c ->
            (0 until c.childCount).map { c.getChildAt(it) }
        } ?: return

        for ((i, item) in items.withIndex()) {
            item.alpha = 0f
            item.translationY = 80f
            item.postDelayed({
                item.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(400 + i * 80L)
                    .setInterpolator(DecelerateInterpolator())
                    .withLayer()
                    .start()
            }, 100 + i * 80L)
        }
    }

    private fun setupServers(view: View) {
        data class ServerInfo(val cardId: Int, val nameId: Int, val name: String, val host: String)
        val servers = listOf(
            ServerInfo(R.id.serverItem1, R.id.serverItemName1, "🇯🇵 日本 - Tokyo #1", "jp-tokyo-01.vpnservice.com"),
            ServerInfo(R.id.serverItem2, R.id.serverItemName2, "🇺🇸 美国 - Los Angeles #2", "us-la-02.vpnservice.com"),
            ServerInfo(R.id.serverItem3, R.id.serverItemName3, "🇭🇰 香港 - Hong Kong #3", "hk-hkg-03.vpnservice.com"),
            ServerInfo(R.id.serverItem4, R.id.serverItemName4, "🇸🇬 新加坡 - Singapore #4", "sg-sin-04.vpnservice.com"),
            ServerInfo(R.id.serverItem5, R.id.serverItemName5, "🇰🇷 韩国 - Seoul #5", "kr-sel-05.vpnservice.com")
        )

        for (s in servers) {
            val card = view.findViewById<MaterialCardView>(s.cardId) ?: continue
            val nameView = card.findViewById<TextView>(s.nameId)
            nameView.text = s.name
            card.setOnClickListener {
                animateSelection(card)
                card.postDelayed({
                    onServerSelected(s.name, s.host)
                    dismiss()
                }, 350)
            }
        }
    }

    private fun animateSelection(card: MaterialCardView) {
        card.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(120)
            .withLayer()
            .withEndAction {
                card.animate()
                    .scaleX(1.05f)
                    .scaleY(1.05f)
                    .setDuration(200)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withLayer()
                    .withEndAction {
                        card.animate()
                            .scaleX(0f)
                            .scaleY(0f)
                            .alpha(0f)
                            .setDuration(250)
                            .withLayer()
                            .start()
                    }
                    .start()
            }
            .start()
    }

    override fun getTheme(): Int = R.style.Theme_VpnProxy
}
