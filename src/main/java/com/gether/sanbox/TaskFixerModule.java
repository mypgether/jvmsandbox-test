package com.gether.sanbox;

import com.alibaba.jvm.sandbox.api.Information;
import com.alibaba.jvm.sandbox.api.Module;
import com.alibaba.jvm.sandbox.api.ProcessControlException;
import com.alibaba.jvm.sandbox.api.event.Event;
import com.alibaba.jvm.sandbox.api.filter.NameRegexFilter;
import com.alibaba.jvm.sandbox.api.http.Http;
import com.alibaba.jvm.sandbox.api.listener.EventListener;
import com.alibaba.jvm.sandbox.api.resource.ModuleEventWatcher;

import javax.annotation.Resource;

/**
 * 修复损坏的钟模块
 */
@Information(id = "task-fixer", author = "gether",version = "0.0.1")
public class TaskFixerModule implements Module {
    public TaskFixerModule() {
    }

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    @Http("/repairCheckState")
    public void repairCheckState() {
        moduleEventWatcher.watch(
                // 匹配到Clock$BrokenClock#checkState()
                new NameRegexFilter("com\\.gether\\.bigdata\\.schedule\\.FlowStatisticTask", "getNowTime"),
                // 监听THROWS事件并且改变原有方法抛出异常为正常返回
                new EventListener() {
                    public void onEvent(Event event) throws Throwable {
                        // 立即返回
                        ProcessControlException.throwReturnImmediately(10000);
                    }
                },
                // 指定监听的事件为抛出异常
                Event.Type.THROWS
        );
    }
}