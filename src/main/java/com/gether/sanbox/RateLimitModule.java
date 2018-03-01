package com.gether.sanbox;

import com.alibaba.jvm.sandbox.api.Information;
import com.alibaba.jvm.sandbox.api.Module;
import com.alibaba.jvm.sandbox.api.ProcessController;
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
import com.google.common.util.concurrent.RateLimiter;
import org.kohsuke.MetaInfServices;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@MetaInfServices(Module.class)
@Information(id = "rateLimit", author = "gether",version = "0.0.1")
public class RateLimitModule extends HttpSupported implements Module {

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    /*
     * 速率控制
     * -d 'rateLimit/rateLimit?class=<CLASS>&method=<METHOD>&r=<RATE>'
     */
    @Http("/rateLimit")
    public void rateLimit(final HttpServletRequest req, final HttpServletResponse resp) throws HttpErrorCodeException, IOException {
        final Printer printer = new ConcurrentLinkedQueuePrinter(resp.getWriter());

        try {
            final String clazz = getParameter(req, "class");
            final String method = getParameter(req, "method");
            final double rate = getParameter(req, "r", Double.class);

            final EventWatcher watcher = new EventWatchBuilder(moduleEventWatcher)
                    .onClass(clazz)
                    .includeSubClasses()
                    .includeBootstrap()
                    .onBehavior(method)
                    .onWatching()
                    .withProgress(new ProgressPrinter(printer))
                    .onWatch(new EventListener() {
                        // 设定一个本次拦截共享的速率限制器，所有被匹配上的类的入口
                        // 将会共同被同一个速率限速！
                        final RateLimiter limiter = RateLimiter.create(rate);

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

                            // 如果是顶层的调用，必须通过流控获取继续调用的门票
                            // 没有拿到门票的就让他快速失败掉
                            if (!limiter.tryAcquire()) {
                                printer.println(String.format(
                                        "%s.%s will be limit by rate: %s on %s",
                                        bEvent.javaClassName,
                                        bEvent.javaMethodName,
                                        rate,
                                        Thread.currentThread().getName()
                                ));
                                ProcessController.throwsImmediately(new RuntimeException("rate-limit by Ralph!!!"));
                            }
                        }
                    }, Event.Type.BEFORE);

            // --- 等待结束 ---
            try {
                printer.println(String.format(
                        "rate-limit on [%s#%s] rate:%.2f(TPS).\nPress CTRL_C abort it!",
                        clazz,
                        method,
                        rate
                ));
                printer.waitingForBroken();
            } finally {
                watcher.onUnWatched();
            }
        } catch (HttpErrorCodeException hece) {
            resp.sendError(hece.getCode(), hece.getMessage());
            return;
        }
    }
}