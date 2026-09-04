package com.crossclip.app.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

object PermissionHelper {

    /**
     * 跳转至系统权限管理页面（包含剪贴板读取设置）
     */
    fun openClipboardPermissionSetting(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        var intent: Intent? = null

        try {
            when {
                // 小米 / Redmi / HyperOS / MIUI
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                    intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                        setClassName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.permissions.PermissionsEditorActivity"
                        )
                        putExtra("extra_pkgname", context.packageName)
                    }
                }
                // OPPO / OnePlus / Realme (ColorOS)
                manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> {
                    intent = Intent().apply {
                        component = ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.permission.PermissionManagerActivity"
                        )
                        putExtra("pkg_name", context.packageName)
                    }
                }
                // vivo / iQOO (OriginOS / FuntouchOS)
                manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                    intent = Intent().apply {
                        component = ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"
                        )
                        putExtra("packagename", context.packageName)
                    }
                }
                // 华为 / 荣耀 (HarmonyOS / EMUI)
                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    intent = Intent().apply {
                        component = ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.permissionmanager.ui.MainActivity"
                        )
                    }
                }
            }

            if (intent != null && intent.resolveActivity(context.packageManager) != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
        } catch (e: Exception) {
            // 忽略厂商私有 Intent 异常，回退到标准应用详情页
        }

        // 标准回退：系统应用详情设置页
        val defaultIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(defaultIntent)
        Toast.makeText(context, "请在权限管理中将【剪贴板】设置为【始终允许】", Toast.LENGTH_LONG).show()
    }

    /**
     * 申请电池无限制（忽略电池优化，防止息屏被杀）
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }
        } else {
            Toast.makeText(context, "已处于电池无限制白名单", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 针对各大国产定制系统与原生系统，精确定位至“后台耗电管理 / 省电策略”页面。
     * 将默认的“智能控制后台耗电 / 智能限制”改为“允许后台耗电 / 无限制”，防止被系统深度冻结。
     */
    fun openBatteryKeepAliveSettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val packageName = context.packageName
        val pm = context.packageManager

        // 构建按优先级排查的候选 Intent 列表
        val intentList = mutableListOf<Pair<Intent, String>>()

        when {
            // 1. 小米 / 红米 / POCO (MIUI / HyperOS 澎湃OS)
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                // 顶级直达：神隐模式单应用设置（直接展示 4 选 1：无限制 / 智能推荐 / 限制后台）
                val hiddenApp = Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY").apply {
                    component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    putExtra("package_name", packageName)
                    putExtra("package_label", "CrossClip")
                }
                intentList.add(hiddenApp to "请勾选【无限制】以彻底免除后台冻结")

                // 备选：神隐模式列表
                val hideMode = Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                    component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.PowerHideModeAppList")
                }
                intentList.add(hideMode to "请在列表中找到 CrossClip 并设为【无限制】")

                // 备选：自启动管理
                val autoStart = Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                    putExtra("extra_pkgname", packageName)
                }
                intentList.add(autoStart to "请开启 CrossClip 自启动")
            }

            // 2. vivo / iQOO (OriginOS / FuntouchOS)
            // 典型路径：应用管理 -> CrossClip -> 电量 -> 后台耗电管理 -> 允许后台高耗电
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                // 后台高耗电管理入口
                val vivoAbe = Intent().apply {
                    component = ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
                    putExtra("package", packageName)
                    putExtra("pkg", packageName)
                }
                intentList.add(vivoAbe to "路径：后台耗电管理 -> 勾选【允许后台高耗电】")

                val iqooPower = Intent().apply {
                    component = ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")
                }
                intentList.add(iqooPower to "路径：后台耗电管理 -> 勾选【允许后台高耗电】")

                val vivoPerm = Intent().apply {
                    component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")
                    putExtra("packagename", packageName)
                }
                intentList.add(vivoPerm to "路径：电量 -> 后台耗电管理 -> 勾选【允许后台高耗电】")
            }

            // 3. OPPO / OnePlus / Realme (ColorOS / OxygenOS / realme UI)
            // 典型路径：应用管理 -> CrossClip -> 耗电管理 -> 允许后台活动
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> {
                val oplusBattery = Intent().apply {
                    component = ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity")
                    putExtra("package_name", packageName)
                }
                intentList.add(oplusBattery to "路径：耗电管理 -> 开启【允许完全后台行为 / 允许后台活动】")

                val colorSingle = Intent().apply {
                    component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.singlepage.PermissionSinglePageActivity")
                    putExtra("package_name", packageName)
                }
                intentList.add(colorSingle to "路径：耗电管理 -> 开启【允许后台活动】")

                val colorStartup = Intent().apply {
                    component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                }
                intentList.add(colorStartup to "请允许 CrossClip 启动与后台运行")
            }

            // 4. 华为 / 荣耀 (HarmonyOS / EMUI / MagicOS)
            // 典型路径：应用启动管理 -> 关闭自动管理 -> 勾选允许后台活动
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                val hwStartup = Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                }
                intentList.add(hwStartup to "路径：应用启动管理 -> 将 CrossClip 关闭【自动管理】，勾选【允许后台活动】")

                val honorStartup = Intent().apply {
                    component = ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                }
                intentList.add(honorStartup to "路径：应用启动管理 -> 将 CrossClip 关闭【自动管理】，勾选【允许后台活动】")

                val hwProtect = Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                }
                intentList.add(hwProtect to "请将 CrossClip 加入锁屏受保护应用")
            }

            // 5. 三星 (Samsung OneUI)
            manufacturer.contains("samsung") -> {
                val samBattery = Intent().apply {
                    component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
                }
                intentList.add(samBattery to "请在电池设置中将 CrossClip 移出休眠列表或设为无限制")
            }
        }

        // 依次探测直达 Intent 是否可用
        for ((targetIntent, guideText) in intentList) {
            try {
                if (targetIntent.resolveActivity(pm) != null) {
                    targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(targetIntent)
                    Toast.makeText(context, guideText, Toast.LENGTH_LONG).show()
                    return
                }
            } catch (_: Exception) {}
        }

        // 探测原生忽略电池优化弹窗
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(pm) != null) {
                    context.startActivity(intent)
                    Toast.makeText(context, "请点击【允许】以解除系统耗电与息屏休眠限制", Toast.LENGTH_LONG).show()
                    return
                }
            }
        } catch (_: Exception) {}

        // 通用兜底：直达应用详情页，引导进入“电量 / 电池”
        try {
            val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(detailsIntent)
            Toast.makeText(
                context,
                "精确定位指引：请点击【电量 / 电池】->【后台耗电管理】-> 改为【允许后台耗电 / 无限制】",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(context, "请在系统设置中找到 CrossClip 并开启允许后台高耗电", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 跳转各大厂商的【自启动管理】设置页面，确保开机广播不被系统安全中心拦截
     */
    fun openAutoStartPermissionSetting(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val packageName = context.packageName
        val pm = context.packageManager
        val intentList = mutableListOf<Pair<Intent, String>>()

        when {
            // 小米 / 红米 / HyperOS
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                val miuiAuto = Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                    putExtra("package_name", packageName)
                }
                intentList.add(miuiAuto to "请为 CrossClip 开启【自启动】开关")

                val miuiOpAuto = Intent("miui.intent.action.OP_AUTO_START")
                intentList.add(miuiOpAuto to "请为 CrossClip 开启【自启动】开关")
            }

            // OPPO / 一加 / realme (ColorOS)
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> {
                val colorStartup1 = Intent().apply {
                    component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                }
                intentList.add(colorStartup1 to "请在自启动列表中允许 CrossClip 自启动")

                val oplusStartup = Intent().apply {
                    component = ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")
                }
                intentList.add(oplusStartup to "请在自启动列表中允许 CrossClip 自启动")
            }

            // vivo / iQOO (OriginOS)
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                val vivoBg = Intent().apply {
                    component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
                }
                intentList.add(vivoBg to "请开启 CrossClip【自启动】")

                val vivoWhite = Intent().apply {
                    component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                }
                intentList.add(vivoWhite to "请开启 CrossClip【自启动】")
            }

            // 华为 / 荣耀 (HarmonyOS / MagicOS)
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                val hwStartup = Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                }
                intentList.add(hwStartup to "请关闭【自动管理】，勾选【允许自启动】与【允许后台活动】")

                val honorStartup = Intent().apply {
                    component = ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                }
                intentList.add(honorStartup to "请关闭【自动管理】，勾选【允许自启动】与【允许后台活动】")
            }
        }

        for ((targetIntent, guideText) in intentList) {
            try {
                if (targetIntent.resolveActivity(pm) != null) {
                    targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(targetIntent)
                    Toast.makeText(context, guideText, Toast.LENGTH_LONG).show()
                    return
                }
            } catch (_: Exception) {}
        }

        // 通用兜底
        try {
            val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(detailsIntent)
            Toast.makeText(context, "请在权限管理中开启【自启动】", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "请在系统安全中心中开启 CrossClip 自启动", Toast.LENGTH_LONG).show()
        }
    }
}
