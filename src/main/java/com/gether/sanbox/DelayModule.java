package com.gether.sanbox;

import com.alibaba.jvm.sandbox.api.Information;
import com.alibaba.jvm.sandbox.api.Module;
import com.alibaba.jvm.sandbox.api.event.BeforeEvent;
import com.alibaba.jvm.sandbox.api.event.Event;
import com.alibaba.jvm.sandbox.api.event.InvokeEvent;
import com.alibaba.jvm.sandbox.api.http.Http;
import com.alibaba.jvm.sandbox.api.http.printer.ConcurrentLinkedQueuePrinter;
import com.alibaba.jvm.sandbox.api.http.printer.Printer;
import com.alibaba.jvm.sandbox.api.listener.EventListener;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatchBuilder;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatcher;
import com.alibaba.jvm.sandbox.api.resource.ModuleEventWatcher;
import com.gether.sanbox.base.HttpSupported;
import com.gether.sanbox.base.ProgressPrinter;
import org.kohsuke.MetaInfServices;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@MetaInfServices(Module.class)
@Information(id = "delay", author = "gether",version = "0.0.1")
public class DelayModule extends HttpSupported implements Module {

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    /*
     * 速率控制
     * -d 'delay/delay?class=<CLASS>&method=<METHOD>&d=<DELAY>'
     */
    @Http("/delay")
    public void delay(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final Printer printer = new ConcurrentLinkedQueuePrinter(resp.getWriter());

        try {
            final String clazz = getParameter(req, "class");
            final String method = getParameter(req, "method");
            final Long duration = getParameter(req, "r", Long.class);

            final AtomicBoolean isFinishRef = new AtomicBoolean(false);
            final ReentrantLock lock = new ReentrantLock();
            final Condition condition = lock.newCondition();

            final EventWatcher watcher = new EventWatchBuilder(moduleEventWatcher)
                    .onClass(clazz)
                    .includeSubClasses()
                    .includeBootstrap()
                    .onBehavior(method)
                    .onWatching()
                    .withProgress(new ProgressPrinter(printer))
                    .onWatch(new EventListener() {
                        // 是否一次拦截调用链的入口
                        private boolean isProcessTop(InvokeEvent event) {
                            return event.processId == event.invokeId;
                        }

                        @Override
                        public void onEvent(Event event) throws Throwable {
                            final BeforeEvent bEvent = (BeforeEvent) event;
                            // 不是顶层调用，说明之前已经通过流控的闸门，可以不受到流控的制约
                            if (!isProcessTop(bEvent)) {
                                return;
                            }

                            printer.println(String.format(
                                    "%s.%s will sleep %s seconds on %s",
                                    bEvent.javaClassName,
                                    bEvent.javaMethodName,
                                    duration,
                                    Thread.currentThread().getName()
                            ));

                            try {
                                lock.lock();
                                // 如果已经结束，则放弃本次请求
                                if (isFinishRef.get()) {
                                    return;
                                }
                                condition.await(duration, TimeUnit.SECONDS);
                            } finally {
                                lock.unlock();
                            }
                        }
                    }, Event.Type.BEFORE);

            // --- 等待结束 ---
            try {
                printer.println(String.format(
                        "delay on [%s#%s] time:%s(s).\nPress CTRL_C abort it!",
                        clazz,
                        method,
                        duration
                ));
                printer.waitingForBroken();
            } finally {
                try {
                    lock.lock();
                    isFinishRef.set(true);
                    condition.signalAll();
                } finally {
                    lock.unlock();
                }
                watcher.onUnWatched();
            }
        } catch (HttpErrorCodeException hece) {
            resp.sendError(hece.getCode(), hece.getMessage());
            return;
        }
    }
}