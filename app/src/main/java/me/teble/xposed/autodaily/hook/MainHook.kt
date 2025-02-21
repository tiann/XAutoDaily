package me.teble.xposed.autodaily.hook

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.XModuleResources
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.emptyParam
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import me.teble.xposed.autodaily.BuildConfig
import me.teble.xposed.autodaily.R
import me.teble.xposed.autodaily.config.*
import me.teble.xposed.autodaily.dex.utils.DexKit.locateClasses
import me.teble.xposed.autodaily.hook.CoreServiceHook.Companion.CORE_SERVICE_FLAG
import me.teble.xposed.autodaily.hook.base.*
import me.teble.xposed.autodaily.hook.config.Config
import me.teble.xposed.autodaily.hook.config.Config.confuseInfo
import me.teble.xposed.autodaily.hook.config.Config.hooksVersion
import me.teble.xposed.autodaily.hook.enums.QQTypeEnum
import me.teble.xposed.autodaily.hook.proxy.ProxyManager
import me.teble.xposed.autodaily.hook.proxy.activity.injectRes
import me.teble.xposed.autodaily.hook.utils.ToastUtil
import me.teble.xposed.autodaily.utils.LogUtil
import me.teble.xposed.autodaily.utils.new
import java.util.concurrent.CompletableFuture.runAsync

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

//    private lateinit var subHookClasses: Set<String>

    private val subHookClasses: Set<Class<out BaseHook>> = setOf(
        FromServiceMsgHook::class.java,
        QLogHook::class.java,
        QQSettingSettingActivityHook::class.java,
        SplashActivityHook::class.java,
        ToServiceMsgHook::class.java,
        BugHook::class.java,
//        CoreServiceHook::class.java,
    )
    private lateinit var mLoadPackageParam: LoadPackageParam
    private lateinit var mStartupParam: IXposedHookZygoteInit.StartupParam
    private var dexIsInit = false

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        mStartupParam = startupParam
        modulePath = startupParam.modulePath
        moduleRes = XModuleResources.createInstance(modulePath, null)
        EzXHelperInit.initZygote(startupParam)
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        mLoadPackageParam = loadPackageParam
        val packageName = loadPackageParam.packageName
        when {
            packageName == PACKAGE_NAME_SELF -> {
                ModuleHook.hookSelf()
            }
            QQTypeEnum.contain(packageName) -> {
                EzXHelperInit.initHandleLoadPackage(mLoadPackageParam)
                EzXHelperInit.setLogTag("XALog")
                EzXHelperInit.setToastTag("XALog")
            }
        }

        if (QQTypeEnum.contain(loadPackageParam.packageName)) {
            hostPackageName = loadPackageParam.packageName
            hostProcessName = loadPackageParam.processName
            hostClassLoader = loadPackageParam.classLoader

            findMethod(loadPackageParam.classLoader.loadClass(BaseApplicationImpl)) {
                name == "onCreate"
            }.hookBefore {
                if (hostInit) return@hookBefore
                runCatching {
                    hostApp = it.thisObject as Application
                    EzXHelperInit.initAppContext(hostApp)
                    hostClassLoader = hostApp.classLoader
                    if (ProcUtil.procType == ProcUtil.MAIN) {
                        // MMKV
                        Config.init()
                        injectClassLoader(hostClassLoader)
                        LogUtil.i("qq version -> ${hostAppName}($hostVersionCode)")
                        LogUtil.i("module version -> ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
                        LogUtil.d("init ActivityProxyManager")
                        ProxyManager.init
                        CoreServiceHook().coreServiceHook()
                        asyncHook()
                    }
                }.onFailure { Log.e("XALog", it.stackTraceToString()) }
            }
            // TODO 分进程处理
            if (loadPackageParam.processName == loadPackageParam.packageName) {
                doInit()
            }
            if (loadPackageParam.processName.endsWith("tool")) {
                Log.d("XALog", "tool进程：" + loadPackageParam.processName)
                toolsHook()
            }
        }
    }

    private var hookIsInit: Boolean = false

    private fun toolsHook() {
        val cmdClass: Class<*> by lazy { load(DataMigrationService)!! }
        val coreServiceClass: Class<*> by lazy { load(CoreService)!! }
        findMethod(cmdClass) {
            name == "onStartCommand"
        }.hookAfter {
            val args = it.args
            val context = it.thisObject as Service
            val intent = args[0] as Intent?
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                var builder: NotificationCompat.Builder
                val channelId = "me.teble.xposed.autodaily.XA_TOOLS_FOREST_NOTIFY_CHANNEL"
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
                    val notificationChannel = NotificationChannel(
                        channelId, "XAutoDaily",
                        NotificationManager.IMPORTANCE_LOW).apply {
                        enableLights(false)
                        enableVibration(false)
                        setShowBadge(false)
                    }
                    notificationManager.createNotificationChannel(notificationChannel)
                    builder = NotificationCompat.Builder(context,
                        channelId
                    )
                } else {
                    @Suppress("DEPRECATION")
                    builder = NotificationCompat.Builder(context)
                        .setPriority(Notification.PRIORITY_LOW)
                }
                builder = builder.setContentTitle("XAutoDaily")
                    .setSmallIcon(R.drawable.icon_x_auto_daily_2)
                    .setOngoing(false)
                    .setShowWhen(true)
                val notification = builder.setContentText("正在唤醒主进程").build()
                context.startForeground(1, notification)
                notificationManager.cancelAll()
            }
            context.startService(Intent(context, coreServiceClass).apply {
                intent?.extras?.let { extra ->
                    putExtras(extra)
                }
            })
            (it.thisObject as Service).stopSelf()
        }
    }

    private fun asyncHook() {
        runAsync {
            //加载资源注入
            LogUtil.d("injectRes")
            injectRes(hostContext.resources)
            // dex相关
            LogUtil.d("doDexInit")
            doDexInit()
            //初始化hook
            LogUtil.d("initHook")
            initHook()
            moduleLoadSuccess = true
            runCatching {
                hostApp.startService(Intent(hostApp, load(CoreService)).apply {
                    putExtra(CORE_SERVICE_FLAG, "$")
                })
            }.onFailure { LogUtil.e(it, "start CoreService failed") }
        }
    }

    private fun doInit() {
        val encumberStep = LoadData
        findMethod(encumberStep) { returnType == Boolean::class.java && emptyParam }.hookAfter {
            // 防止hook多次被执行
            runCatching {
                if (hookIsInit) {
                    return@hookAfter
                }
                hookIsInit = true
                // 等待hook执行完毕
                while (!moduleLoadSuccess) {
                    Thread.sleep(200)
                }
            }.onFailure {
                LogUtil.e(it)
                ToastUtil.send("初始化失败: " + it.stackTraceToString())
            }
        }
    }

    private fun doDexInit() {
        val cache = Config.classCache
        // dex解析
        // module dex
//        if (cache.getInt("moduleVersion", 0) < BuildConfig.VERSION_CODE || BuildConfig.DEBUG) {
//            LogUtil.d(TAG, "模块版本更新/Debug版本，重新定位Hook子类")
//            subHookClasses =
//                findSubClasses(MainHook::class.java.classLoader!!, BaseHook::class.java).toSet()
//            cache.putStringSet("subHookClasses", subHookClasses)
//            cache.putInt("moduleVersion", BuildConfig.VERSION_CODE)
//        } else {
//            LogUtil.d(TAG, "缓存生效，跳过hook子类解析")
//            subHookClasses = cache.getStringSet("subHookClasses") ?: emptySet()
//        }
        // qq dex
        val confuseInfoKeys = confuseInfo.keys
        val needLocateClasses = mutableSetOf<String>()
        // 清空混淆缓存
        if (cache.getInt("hooksVersion", 0) < hooksVersion) {
            LogUtil.d("清空Hooks缓存")
            cache.clearAll()
            cache.putInt("hooksVersion", hooksVersion)
        }
        confuseInfo.forEach {
            val key = "${it.key}#hash"
            val hash = it.value.hashCode()
            val cacheHash = cache.getInt(key, 0)
            LogUtil.d("${it.key} cacheHash -> $cacheHash")
            LogUtil.d("${it.key} hash -> $hash")
            // 加入修改了特征的类
            if (cacheHash != hash) {
                cache.putInt(key, hash)
                needLocateClasses.add(it.key)
                LogUtil.d("need locate -> ${it.key}")
            }
            // 加入没有被搜索过的类
            if (!cache.contains("${it.key}#${hostVersionCode}")) {
                needLocateClasses.add(it.key)
                LogUtil.d("cache not found: ${it.key}#${hostVersionCode}")
            }
        }
        // 尝试获取，成功则加入新版缓存
        needLocateClasses.removeIf { classSimpleName ->
            LogUtil.d("尝试获取类：$classSimpleName")
            try {
                val cls = hostClassLoader.loadClass(classSimpleName)
                LogUtil.d("尝试获取类成功 -> ${cls.canonicalName}")
                cache.putString("$classSimpleName#${hostVersionCode}", classSimpleName)
                return@removeIf true
            } catch (e: Exception) {
                return@removeIf false
            }
        }
        if (needLocateClasses.isEmpty()) {
            return
        }
        dexIsInit = true
        LogUtil.log("needLocateClasses -> $needLocateClasses")
        ToastUtil.send("正在尝试定位QQ混淆类，可能需要等待一段时间", true)
        val info = needLocateClasses.associateWith { confuseInfo[it] }
        val startTime = System.currentTimeMillis()
        val locateRes = locateClasses(info)
        val useTime = System.currentTimeMillis() - startTime
        var locateNum = 0
        locateRes.forEach {
            if (it.value.size == 1) {
                LogUtil.i("locate info: ${it.key} -> ${it.value[0]}")
                cache.putString("${it.key}#${hostVersionCode}", it.value[0])
                locateNum++
            } else {
                LogUtil.w("locate not instance class: ${it.key} -> ${it.value}")
                // 保存为空字符串，表示已经搜索过，下次不再搜索
                cache.putString("${it.key}#${hostVersionCode}", "")
            }
        }
        cache.putStringSet("confuseClasses", confuseInfoKeys)
        ToastUtil.send("dex搜索完毕，成功${locateNum}个，失败${needLocateClasses.size - locateNum}个，耗时${useTime}ms")
        dexIsInit = false
    }

    private fun initHook() {
        for (cls in subHookClasses) {
            try {
//                loadAs<BaseHook>(cls, Global.moduleClassLoader).new().init()
                cls.new().init()
            } catch (e: Exception) {
                LogUtil.e(e)
            }
        }
        LogUtil.i("模块加载完毕")
    }
}