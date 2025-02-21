package me.teble.xposed.autodaily.task.util

import android.annotation.SuppressLint
import cn.hutool.core.io.FileUtil
import cn.hutool.core.util.ReUtil
import com.charleskorn.kaml.Yaml
import me.teble.xposed.autodaily.BuildConfig
import me.teble.xposed.autodaily.config.NOTICE
import me.teble.xposed.autodaily.config.XA_API_URL
import me.teble.xposed.autodaily.hook.base.hostContext
import me.teble.xposed.autodaily.hook.config.Config.accountConfig
import me.teble.xposed.autodaily.hook.config.Config.xaConfig
import me.teble.xposed.autodaily.hook.utils.ToastUtil
import me.teble.xposed.autodaily.task.cron.pattent.CronPattern
import me.teble.xposed.autodaily.task.cron.pattent.CronPatternUtil
import me.teble.xposed.autodaily.task.model.*
import me.teble.xposed.autodaily.task.util.Const.CONFIG_VERSION
import me.teble.xposed.autodaily.ui.ConfUnit
import me.teble.xposed.autodaily.ui.ConfUnit.configVersion
import me.teble.xposed.autodaily.ui.ConfUnit.lastFetchTime
import me.teble.xposed.autodaily.ui.ConfUnit.versionInfoCache
import me.teble.xposed.autodaily.ui.enable
import me.teble.xposed.autodaily.ui.lastExecTime
import me.teble.xposed.autodaily.ui.nextShouldExecTime
import me.teble.xposed.autodaily.utils.*
import java.io.File
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern

object ConfigUtil {
    /**
     * 获取配置版本列表：https://data.jsdelivr.com/v1/package/gh/teble/XAutoDaily-Conf
     * {"tags": [], "versions": ["1"]}
     *
     * 配置文件下载链接：https://cdn.jsdelivr.net/gh/teble/XAutoDaily-Conf@1/xa_conf
     */

    private val confDir = File(hostContext.filesDir, "xa_conf")
    private val MIN_APP_VERSION_REG = Pattern.compile("minAppVersion:\\s+(\\d+)")
    private val CONFIG_VERSION_REG = Pattern.compile("version:\\s+(\\d+)")

    private val lock = ReentrantLock()
    private var _conf: TaskProperties? = null

    init {
        loadLib()
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun loadLib() {
        val xaLibDir = File(hostContext.filesDir, "xa_lib")
        if (xaLibDir.isFile) {
            xaLibDir.delete()
        }
        if (!xaLibDir.exists()) {
            xaLibDir.mkdirs()
        }
        val soFilePath = NativeUtil.getNativeLibrary(hostContext, "xa_decrypt").absolutePath
        LogUtil.i("loadLib: $soFilePath")
        try {
            System.load(soFilePath)
        } catch (e: Throwable) {
            LogUtil.e(e, "加载so失败 -> $soFilePath")
            throw e
        }
    }

    private external fun decryptXAConf(encConfBytes: ByteArray): ByteArray

    external fun getTencentDigest(value: String): String

    external fun getMd5Hex(value: String): String

    fun checkConfigUpdate(currentConfigVersion: Int): String? {
        try {
            ToastUtil.send("正在检测更新")
            LogUtil.i("正在检测更新")
            val res = "https://data.jsdelivr.com/v1/package/gh/teble/XAutoDaily-Conf".get()
            val packageData: PackageData = res.parse()
            if (packageData.versions.isNotEmpty() && currentConfigVersion < packageData.versions[0].toInt()) {
                return packageData.versions[0]
            }
        } catch (e: Exception) {
            LogUtil.e(e)
        }
        return null
    }

    fun updateCdnConfig(newConfVersion: Int): Boolean {
        val encRes = "https://cdn.jsdelivr.net/gh/teble/XAutoDaily-Conf@${newConfVersion}/xa_conf".get()
        val res = decodeConfStr(encRes)
        val minAppVersion = ReUtil.getGroup1(MIN_APP_VERSION_REG, res).toInt()
        if (minAppVersion > BuildConfig.VERSION_CODE) {
            ToastUtil.send("插件版本号低于${minAppVersion}，无法使用v${newConfVersion}版本的配置", true)
            LogUtil.i("插件版本号低于${minAppVersion}，无法使用v${newConfVersion}版本的配置")
            return false
        }
        saveConfFile(encRes, newConfVersion)
        ToastUtil.send("配置文件更新完毕，如有选项更新，请前往配置目录进行勾选")
        return true
    }

    fun checkUpdate(showToast: Boolean): Boolean {
        val info = fetchUpdateInfo()
        info?.let {
            val currConfVer = configVersion
            if (BuildConfig.VERSION_CODE < info.appVersion) {
                if (showToast) {
                    ToastUtil.send("插件版本存在更新")
                }
                return true
            }
            if (currConfVer < info.confVersion) {
                if (BuildConfig.VERSION_CODE >= info.minAppVersion) {
                    updateConfig(info.confUrl, showToast)
                } else {
                    ToastUtil.send("插件版本过低无法更新配置")
                }
                return false
            }
        } ?: let {
            if (showToast) {
                ToastUtil.send("检查更新失败")
            }
        }
        if (showToast) {
            ToastUtil.send("当前插件与配置均是最新版本")
        }
        return false
    }

    private fun updateConfig(confUrl: String, showToast: Boolean): Boolean {
        try {
            val encRes = confUrl.get()
            val res = decodeConfStr(encRes)
            val minAppVersion = ReUtil.getGroup1(MIN_APP_VERSION_REG, res).toInt()
            val confVersion = ReUtil.getGroup1(CONFIG_VERSION_REG, res).toInt()
            if (minAppVersion > BuildConfig.VERSION_CODE) {
                if (showToast) {
                    ToastUtil.send("插件版本号低于${minAppVersion}，无法使用v${confVersion}版本的配置", true)
                }
                LogUtil.i("插件版本号低于${minAppVersion}，无法使用v${confVersion}版本的配置")
                return false
            }
            if (confVersion <= configVersion) {
                if (showToast) {
                    ToastUtil.send("当前配置已是最新，无需更新")
                }
                return false
            }
            saveConfFile(encRes, confVersion)
            ConfUnit.needShowUpdateLog = true
            ToastUtil.send("配置文件更新完毕，如有选项更新，请前往配置目录进行勾选")
            return true
        } catch (e: Exception) {
            LogUtil.e(e)
            ToastUtil.send("更新配置文件失败，详情请看日志")
            return false
        }
    }

    private fun decodeConfStr(encodeConfStr: String): String? {
        LogUtil.d("conf length -> ${encodeConfStr.length}")
        val res = decryptXAConf(encodeConfStr.toByteArray())
        if (res.isEmpty()) {
            LogUtil.w("解密配置文件失败，请检查插件是否为最新版本")
            return null
        }
        return String(res)
    }

    private fun saveConfFile(encConfStr: String, configVersion: Int): Boolean {
        try {
            if (confDir.isFile) {
                confDir.delete()
            }
            if (!confDir.exists()) {
                confDir.mkdirs()
            }
            val propertiesFile = File(confDir, "xa_conf")
            if (!propertiesFile.exists()) {
                propertiesFile.createNewFile()
            }
            FileUtil.writeUtf8String(encConfStr, propertiesFile)
            ConfUnit.configVersion = configVersion
            xaConfig.putInt(CONFIG_VERSION, configVersion)
            return true
        } catch (e: Exception) {
            LogUtil.e(e)
            ToastUtil.send("保存配置文件失败，详情请看日志")
            return false
        } finally {
            // clear cache
            _conf = null
        }
    }

    private fun getDefaultConf(): String {
        return getTextFromModuleAssets("default_conf")
    }

    fun loadSaveConf(): TaskProperties {
        if (_conf != null) {
            return _conf as TaskProperties
        }
        var conf: TaskProperties? = null
        val propertiesFile = File(confDir, "xa_conf")
        if (propertiesFile.exists()) {
            conf = loadConf(FileUtil.readUtf8String(propertiesFile))
        }
        val encodeConfig = getDefaultConf()
        val defaultConf = loadConf(encodeConfig)!!
        if (conf == null) {
            conf = defaultConf
            ToastUtil.send("配置文件不存在/加载失败，正在解压默认配置，详情请看日志")
            LogUtil.i("defaultConf version -> ${defaultConf.version}")
            saveConfFile(encodeConfig, defaultConf.version)
            ConfUnit.needShowUpdateLog = true
        } else {
            // 配置正常加载，表示配置版本与当前model一致，判断内置文件版本是否需要覆盖当前配置
            // 内置配置版本与本地配置版本一致的情况，如果hashcode不一致，内置覆盖本地
            // 内置版本 > 本地配置版本，内置覆盖本地
            if (conf.version <= defaultConf.version && conf != defaultConf) {
                if (conf.version < defaultConf.version) {
                    ConfUnit.needShowUpdateLog = true
                }
                conf = defaultConf
                saveConfFile(encodeConfig, defaultConf.version)
                ToastUtil.send("正在解压内置配置文件，如有选项更新，请前往配置目录进行勾选")
            }
        }
        _conf = conf
        return conf
    }

    private fun loadConf(encodeConfStr: String): TaskProperties? {
        try {
            decodeConfStr(encodeConfStr)?.let { decodeConfStr ->
                val version = readMinVersion(decodeConfStr)
                if (version > BuildConfig.VERSION_CODE) {
                    LogUtil.i("插件版本过低，无法加载配置。配置要求最低插件版本: ${version}，当前插件版本: ${BuildConfig.VERSION_CODE}")
//                    ToastUtil.send("插件版本过低，无法加载配置。配置要求最低插件版本: ${version}，当前插件版本: ${BuildConfig.VERSION_CODE}", true)
                    return null
                }
                // 版本不对应抛出异常
                return Yaml.default.decodeFromString(TaskProperties.serializer(), decodeConfStr)
            }
        } catch (e: Exception) {
            LogUtil.e(e)
//            ToastUtil.send("配置加载失败，请检查插件是否为最新版本", true)
        }
        return null
    }

    fun readMinVersion(confStr: String): Int {
        var minAppVersion = 0
        val str = ReUtil.getGroup1(MIN_APP_VERSION_REG, confStr)
        if (str.isNotEmpty()) {
            minAppVersion = str.toInt()
        }
        return minAppVersion
    }

    fun changeSignButton(key: String, boolean: Boolean) {
        accountConfig.putBoolean(key, boolean)
    }

    fun getCurrentExecTaskNum(): Int {
        var num = 0
        val conf = loadSaveConf()
        val nowStr = TimeUtil.getCNDate().format().substring(0, 10)
        conf.taskGroups.forEach { taskGroup ->
            taskGroup.tasks.forEach { task ->
                val time = task.lastExecTime ?: ""
                if (time.startsWith(nowStr)) {
                    num++
                }
            }
        }
        return num
    }

    fun fetchUpdateInfo(): VersionInfo? {
        return try {
            val text = "$XA_API_URL$NOTICE".get()
            LogUtil.d("getNotice -> $text")
            val res: Result = text.parse()
            versionInfoCache = res.data
            lastFetchTime = System.currentTimeMillis()
            return res.data
        } catch (e: Exception) {
            LogUtil.e(e, "拉取公告失败：")
            null
        }
    }

    fun checkExecuteTask(task: Task): Boolean {
        val enabled = task.enable
        if (!enabled) {
            return false
        }
        // 获取上次执行任务时间
        val lastExecTime = parseDate(task.lastExecTime)
        // 不保证一定在有效时间内执行
        val now = TimeUtil.getCNDate()
        val nextShouldExecTime =
            task.nextShouldExecTime ?: let {
                val realCron = TaskUtil.getRealCron(task)
                ///////////////////////////////////////////////////////////////////////////////////////
                val time = CronPatternUtil.nextDateAfter(
                    TimeZone.getTimeZone("GMT+8"),
                    CronPattern(realCron),
                    Date(TimeUtil.cnTimeMillis() + 1),
                    true)!!
                task.nextShouldExecTime = Date(time.time - TimeUtil.offsetTime).format()
                Date(time.time + TimeUtil.offsetTime).format()
            }
        lastExecTime ?: let {
            // 第一次执行任务，如果下次执行时间不在当天，则立即执行
            if (task.nextShouldExecTime?.substring(0, 10) != now.format().substring(0, 10)) {
                return true
            }
        }
        if (nextShouldExecTime <= now.format()) {
            return true
        }
        return false
    }
}
