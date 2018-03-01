package com.gether.sanbox;

import com.alibaba.jvm.sandbox.api.Information;
import com.alibaba.jvm.sandbox.api.Module;
import com.alibaba.jvm.sandbox.api.event.BeforeEvent;
import com.alibaba.jvm.sandbox.api.event.Event;
import com.alibaba.jvm.sandbox.api.http.Http;
import com.alibaba.jvm.sandbox.api.http.printer.ConcurrentLinkedQueuePrinter;
import com.alibaba.jvm.sandbox.api.http.printer.Printer;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatchBuilder;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatcher;
import com.alibaba.jvm.sandbox.api.resource.ModuleEventWatcher;
import com.gether.sanbox.base.HttpSupported;
import com.gether.sanbox.base.ProgressPrinter;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.kohsuke.MetaInfServices;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Field;

import static javax.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;

@MetaInfServices(Module.class)
@Information(id = "pmf", author = "gether", version = "0.0.1")
public class ParameterModifyModule extends HttpSupported implements Module {

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    /*
     * 修改参数
     * -d 'pmf/pmf?uid=<UID>'
     */
    @Http("/pmf")
    public void parameterModify(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        try {
            final Printer printer = new ConcurrentLinkedQueuePrinter(resp.getWriter());
            final Long uid = getParameter(req, "uid", Long.class);

            final String clazz = "com.gether.bigdata.dao.mapper.ODeviceMapper";
            final String method = "insertSelective";
            EventWatcher watcher = new EventWatchBuilder(moduleEventWatcher)
                    .onClass(clazz)
                    .includeBootstrap()
                    .includeSubClasses()
                    .onBehavior(method)
                    .onWatching()
                    .withProgress(new ProgressPrinter(printer))
                    .onWatch(event -> {
                        BeforeEvent bEvent = (BeforeEvent) event;
                        Object device = bEvent.argumentArray[0];
                        Field uidFiled = FieldUtils.getField(device.getClass(), "uid", true);
                        FieldUtils.writeField(uidFiled, device, uid, true);
                        bEvent.changeParameter(0, device);
                    }, Event.Type.BEFORE);

            try {
                printer.println(String.format(
                        "modify on [%s#%s] \nPress CTRL_C abort it!",
                        clazz,
                        method
                ));
                printer.waitingForBroken();
            } finally {
                watcher.onUnWatched();
            }
        } catch (HttpErrorCodeException e) {
            resp.sendError(e.getCode(), e.getMessage());
            return;
        } catch (Throwable e) {
            resp.sendError(SC_SERVICE_UNAVAILABLE, e.getMessage());
            return;
        }
    }
}