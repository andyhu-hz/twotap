package com.example.twotap

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 透明 Activity，仅用于引导用户开启无障碍服务权限。
 * 打开设置页后立即关闭自身，整个 App 没有持久 UI。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "TwoTap 服务已激活，按「音量加 + 音量减」触发", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "请在无障碍设置中开启「TwoTap 双指触摸服务」", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        finish()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = "$packageName/${TwoTapService::class.java.name}"
        return enabledServices.split(":").any { it.equals(target, ignoreCase = true) }
    }
}
