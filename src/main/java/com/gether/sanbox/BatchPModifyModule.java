package com.gether.sanbox;

import com.alibaba.fastjson.JSONObject;
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
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.kohsuke.MetaInfServices;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Base64;

import static javax.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;

@MetaInfServices(Module.class)
@Information(id = "bpmf", author = "gether", version = "0.0.1")
public class BatchPModifyModule extends HttpSupported implements Module {

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    /*
     * 修改参数
     * -d 'bpmf/bpmf?clazz=<CLASS>&method=<METHOD>&params=<PARAMS>'
     */
    // -d 'bpmf/bpmf?clazz=com.gether.bigdata.dao.mapper.ODeviceMapper&method=insertSelective&params=eyAiMCI6IHsgInVpZCI6MTIzTCwgImRldmljZWlkIjogImNuY21tdm5kIiwiYXNkIjoxMjMgfSwgIjEiOiB7ICJ1aWQiOiAxMzI0OTgyMzRMLCAiZGV2aWNlaWQiOiAiZGV2aWNlMTIzIiB9IH0='
    // -d 'bpmf/bpmf?clazz=com.gether.bigdata.service.impl.DeviceServiceImpl&method=addDevice&params=eyAiMCI6dHJ1ZX0='
    @Http("/bpmf")
    public void batchParameterModify(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        try {
            final Printer printer = new ConcurrentLinkedQueuePrinter(resp.getWriter());

            final String clazz = getParameter(req, "clazz");
            final String method = getParameter(req, "method");
            final String paramsInput = getParameter(req, "params");
            final String params = new String(Base64.getDecoder().decode(paramsInput));
            EventWatcher watcher = new EventWatchBuilder(moduleEventWatcher)
                    .onClass(clazz)
                    .includeBootstrap()
                    .includeSubClasses()
                    .onBehavior(method)
                    .onWatching()
                    .withProgress(new ProgressPrinter(printer))
                    .onWatch(event -> {
                        BeforeEvent bEvent = (BeforeEvent) event;

                        JSONObject paramsObj = JSONObject.parseObject(params);
                        for (String key : paramsObj.keySet()) {
                            int index = Integer.valueOf(key);
                            Object value = paramsObj.get(key);
                            if (value instanceof JSONObject) {
                                for (String objField : ((JSONObject) value).keySet()) {
                                    changeParameter(bEvent, index, objField, ((JSONObject) value).get(objField));
                                }
                            } else {
                                changeParameter(bEvent, index, null, value);
                            }
                        }
                    }, Event.Type.BEFORE);
            try {
                printer.println(String.format(
                        "modify on [%s#%s] params %s\nPress CTRL_C abort it!",
                        clazz,
                        method,
                        params
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

    /**
     * 修改参数内容，如果fieldName是空，那就直接修改参数；否则当作对象进行修改字段内容
     *
     * @param bEvent     事件
     * @param index      第几个参数
     * @param fieldName  参数的字段名称，如果是空表示直接修改参数内容
     * @param fieldValue 参数的字段修改的内容
     * @throws IllegalAccessException 非法访问异常
     */
    public void changeParameter(BeforeEvent bEvent, int index, String fieldName, Object fieldValue) throws IllegalAccessException {
        if (index >= bEvent.argumentArray.length) {
            return;
        }
        if (StringUtils.isBlank(fieldName)) {
            bEvent.changeParameter(index, fieldValue);
            return;
        }
        Object obj = bEvent.argumentArray[index];
        Field uidFiled = FieldUtils.getField(obj.getClass(), fieldName, true);
        if (uidFiled != null) {
            FieldUtils.writeField(uidFiled, obj, fieldValue, true);
            bEvent.changeParameter(index, obj);
        }
    }
}