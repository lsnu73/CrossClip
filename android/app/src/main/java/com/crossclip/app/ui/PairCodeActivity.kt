package com.crossclip.app.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.crossclip.app.R
import com.crossclip.app.service.SyncForegroundService
import com.crossclip.app.util.DebugLogger
import java.util.Locale

/**
 * 屏 3 · 输入 PIN 码。
 *
 * 6 格配对码由一个隐藏的数字输入框驱动：点击格子拉起数字键盘；填满 6 位后延时 400ms
 * 自动校验。PIN 不匹配（HTTP 403）时 6 格标红 + 抖动一次，420ms 后自动清空。
 */
class PairCodeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_DEVICE_IP = "extra_device_ip"
        const val EXTRA_SAVED_PIN = "extra_saved_pin"
        private const val PIN_LENGTH = 6
    }

    private lateinit var tvPinTitle: TextView
    private lateinit var tvPinDesc: TextView
    private lateinit var pinBoxes: android.view.View
    private val boxes = ArrayList<TextView>(PIN_LENGTH)
    private lateinit var etHidden: EditText
    private lateinit var btnConfirm: Button

    private val handler = Handler(Looper.getMainLooper())
    private var deviceName = ""
    private var deviceIp = ""

    /** 配对请求在途 / 错误抖动窗口：期间忽略输入变化，避免重复发起 */
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_code)

        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "电脑"
        deviceIp = intent.getStringExtra(EXTRA_DEVICE_IP) ?: ""

        tvPinTitle = findViewById(R.id.tv_pin_title)
        tvPinDesc = findViewById(R.id.tv_pin_desc)
        pinBoxes = findViewById(R.id.pin_boxes)
        etHidden = findViewById(R.id.et_hidden_pin)
        btnConfirm = findViewById(R.id.btn_confirm_pair)

        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        val isManual = intent.dataString == null && deviceName == deviceIp
        if (isManual) {
            tvPinTitle.text = "连接电脑"
            tvPinDesc.text = "输入电脑 $deviceIp 托盘显示的 6 位 PIN 码，即可建立加密连接。"
        } else {
            tvPinTitle.text = "桌面端正在请求配对"
            tvPinDesc.text = "在 $deviceName 上显示的 6 位 PIN 码输入到下方，即可建立加密连接。"
        }

        // 6 格：点击任意格拉起数字键盘
        val boxIds = intArrayOf(R.id.box_0, R.id.box_1, R.id.box_2, R.id.box_3, R.id.box_4, R.id.box_5)
        for (id in boxIds) {
            val box = findViewById<TextView>(id)
            boxes.add(box)
            box.setOnClickListener { focusPinInput() }
        }

        etHidden.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy) return
                renderBoxes()
                // 填满 6 位后延时 400ms 自动校验
                val text = s?.toString().orEmpty()
                etHidden.removeCallbacks(autoVerifyRunnable)
                if (text.length == PIN_LENGTH) {
                    etHidden.postDelayed(autoVerifyRunnable, 400)
                }
            }
        })

        btnConfirm.setOnClickListener { attemptConnect() }
        findViewById<TextView>(R.id.btn_rescan).setOnClickListener {
            etHidden.removeCallbacks(autoVerifyRunnable)
            finish()
        }

        // 预填该电脑记忆中的 PIN（命中即自动连接，相当于旧版「一键连接」）
        val savedPin = intent.getStringExtra(EXTRA_SAVED_PIN).orEmpty()
        if (savedPin.length == PIN_LENGTH) {
            etHidden.setText(savedPin)
        } else {
            renderBoxes()
        }

        // 自动拉起数字键盘
        etHidden.postDelayed({ focusPinInput() }, 250)
    }

    private val autoVerifyRunnable = Runnable { attemptConnect() }

    private fun focusPinInput() {
        etHidden.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etHidden, InputMethodManager.SHOW_IMPLICIT)
    }

    /** 按 已填/当前格/空态 三态渲染 6 格 */
    private fun renderBoxes(bad: Boolean = false) {
        val text = etHidden.text?.toString().orEmpty()
        for (i in 0 until PIN_LENGTH) {
            val box = boxes[i]
            val ch = text.getOrNull(i)
            when {
                bad -> {
                    box.setBackgroundResource(R.drawable.bg_pin_box_bad)
                    box.setTextColor(getColor(R.color.danger))
                    box.text = ch?.toString() ?: ""
                }
                ch != null -> {
                    box.setBackgroundResource(R.drawable.bg_pin_box_filled)
                    box.setTextColor(getColor(R.color.text_primary))
                    box.text = ch.toString()
                }
                i == text.length -> {
                    box.setBackgroundResource(R.drawable.bg_pin_box_active)
                    box.setTextColor(getColor(R.color.text_3))
                    box.text = ""
                }
                else -> {
                    box.setBackgroundResource(R.drawable.bg_pin_box)
                    box.setTextColor(getColor(R.color.text_3))
                    box.text = ""
                }
            }
        }
        btnConfirm.isEnabled = text.length == PIN_LENGTH && !busy
    }

    /**
     * 发起配对。按钮置「配对中…」防重复点击；结果回调统一在此处理：
     * 成功 → 回设置根屏；403 → 标红抖动清空；其他失败 → 恢复按钮但保留输入。
     */
    private fun attemptConnect() {
        val pin = etHidden.text?.toString().orEmpty()
        if (pin.length != PIN_LENGTH) {
            Toast.makeText(this, "请输入 6 位 PIN 码", Toast.LENGTH_SHORT).show()
            return
        }
        val service = SyncForegroundService.instance
        if (service == null) {
            Toast.makeText(this, "后台服务未就绪，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }

        busy = true
        btnConfirm.isEnabled = false
        btnConfirm.text = "配对中…"
        hideKeyboard()

        DebugLogger.log("UI", "发起 PIN 码配对: $deviceName ($deviceIp)")
        service.connectWithPin(deviceIp, pin) { success, statusCode, name ->
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                busy = false
                btnConfirm.text = "确认配对"
                if (success) {
                    Toast.makeText(this, "已与 ${name ?: deviceName} 建立连接", Toast.LENGTH_SHORT).show()
                    goBackToSettings()
                } else if (statusCode == 403) {
                    failCode()
                } else {
                    renderBoxes()
                    Toast.makeText(this, "连接超时，请确认手机和电脑在同一局域网", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** PIN 不匹配：6 格标红 + 抖动一次，420ms 后自动清空 */
    private fun failCode() {
        DebugLogger.err("UI", "PIN 码不匹配，输入已自动清空")
        renderBoxes(bad = true)

        val shake = ObjectAnimator.ofFloat(
            pinBoxes, "translationX",
            0f, -7f, 6f, -4f, 3f, 0f
        ).apply {
            duration = 400
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
        // 结束后务必复位，防止个别 ROM 上动画残留偏移
        shake.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                pinBoxes.translationX = 0f
            }
        })

        Toast.makeText(this, "PIN 码不匹配，已自动清空，请重新输入", Toast.LENGTH_LONG).show()
        etHidden.postDelayed({
            busy = false
            etHidden.setText("")
            renderBoxes()
            btnConfirm.isEnabled = false
            focusPinInput()
        }, 420)
    }

    private fun goBackToSettings() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etHidden.windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        etHidden.removeCallbacks(autoVerifyRunnable)
        handler.removeCallbacksAndMessages(null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        etHidden.removeCallbacks(autoVerifyRunnable)
        super.onBackPressed()
    }
}
