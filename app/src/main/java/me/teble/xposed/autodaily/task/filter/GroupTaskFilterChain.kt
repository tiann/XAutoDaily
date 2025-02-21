package me.teble.xposed.autodaily.task.filter

import me.teble.xposed.autodaily.hook.notification.XANotification
import me.teble.xposed.autodaily.task.filter.chain.GroupTaskCheckExecuteFilter
import me.teble.xposed.autodaily.task.filter.chain.GroupTaskExecuteBasicFilter
import me.teble.xposed.autodaily.task.filter.chain.GroupTaskPreFilter
import me.teble.xposed.autodaily.task.filter.chain.GroupTaskRelayBuilderFilter
import me.teble.xposed.autodaily.task.model.Task
import me.teble.xposed.autodaily.task.model.TaskGroup
import me.teble.xposed.autodaily.task.request.enum.ReqType
import me.teble.xposed.autodaily.task.util.TaskUtil
import me.teble.xposed.autodaily.task.util.formatDate
import me.teble.xposed.autodaily.ui.errCount
import me.teble.xposed.autodaily.ui.taskExceptionFlag
import me.teble.xposed.autodaily.utils.LogUtil
import me.teble.xposed.autodaily.utils.TimeUtil
import java.net.SocketTimeoutException
import java.util.*

class GroupTaskFilterChain(
    val taskGroup: TaskGroup
) : FilterChain {

    private var pos = 0
    private val filters: MutableList<GroupTaskFilter> = ArrayList()

    companion object {
        fun build(taskGroup: TaskGroup): FilterChain {
            val chain = GroupTaskFilterChain(taskGroup)
            chain.add(GroupTaskCheckExecuteFilter())
            chain.add(GroupTaskPreFilter())
            chain.add(GroupTaskRelayBuilderFilter())
            chain.add(GroupTaskExecuteBasicFilter())
            return chain
        }
    }

    fun add(groupTaskFilter: GroupTaskFilter) {
        filters.add(groupTaskFilter)
    }

    override fun doFilter(
        relayTaskMap: MutableMap<String, Task>,
        taskList: MutableList<Task>,
        env: MutableMap<String, Any>
    ) {
        if (pos < filters.size) {
            val filter = filters[pos++]
            LogUtil.d("当前filter -> ${filter.TAG}")
            filter.doFilter(relayTaskMap, taskList, env, this)
        } else {
            val reqType = ReqType.getType(taskGroup.type.split("|").first())
            for (task in taskList) {
                var errCount = task.errCount
                if (errCount >= 3) {
                    LogUtil.i("任务${task.id}今日执行错误次数超过3次，跳过执行")
                    continue
                }
                try {
                    XANotification.setContent("正在执行任务${task.id}")
                    TaskUtil.execute(reqType, task, relayTaskMap, env.toMutableMap())
                } catch (e: SocketTimeoutException) {
                    LogUtil.e(e, "执行任务${task.id}异常: ")
                } catch (e: Throwable) {
                    LogUtil.e(e, "执行任务${task.id}异常: ")
                    val currentDate = Date(TimeUtil.localTimeMillis()).formatDate()
                    task.taskExceptionFlag = "$currentDate|${++errCount}"
                }
            }
        }
    }
}