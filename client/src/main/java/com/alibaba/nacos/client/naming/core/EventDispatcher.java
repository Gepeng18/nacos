/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.client.naming.core;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ServiceInfo;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.alibaba.nacos.common.lifecycle.Closeable;
import com.alibaba.nacos.common.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static com.alibaba.nacos.client.utils.LogUtils.NAMING_LOGGER;

/**
 * Event dispatcher.
 * 事件分发器，
 * 其实就是nacos服务端通知过来之后，client先会跟本地缓存的服务实例信息做一下比较，如果发生了变化，
 * 就会整一个服务实例变化的通知给 EventDispatcher 组件，这个组件就会根据服务信息，
 * 然后找到对应的listener，进行执行，它其实就是管着 listener 注册与调用listener 执行调用的。
 *
 * @author xuanyin
 */
@SuppressWarnings("PMD.ThreadPoolCreationRule")
public class EventDispatcher implements Closeable {

    private ExecutorService executor = null;

    /**
     * 当从server获取的注册表与本地注册表不同时，add: com.alibaba.nacos.client.naming.core.HostReactor#processServiceJson
     *
     */
    private final BlockingQueue<ServiceInfo> changedServices = new LinkedBlockingQueue<ServiceInfo>();

    private final ConcurrentMap<String, List<EventListener>> observerMap = new ConcurrentHashMap<String, List<EventListener>>();

    private volatile boolean closed = false;

    public EventDispatcher() {

        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "com.alibaba.nacos.naming.client.listener");
                thread.setDaemon(true);

                return thread;
            }
        });

        this.executor.execute(new Notifier());
    }

    /**
     * Add listener.
     * 将订阅服务信息与listener 放到一个map
     *
     * @param serviceInfo service info
     * @param clusters    clusters
     * @param listener    listener
     */
    public void addListener(ServiceInfo serviceInfo, String clusters, EventListener listener) {

        /**
         * client端主动订阅
         *  naming.subscribe("nacos.test.3", new EventListener() {
         *             @Override
         *             public void onEvent(Event event) {
         *                 System.out.println(((NamingEvent) event).getServiceName());
         *                 System.out.println(((NamingEvent) event).getInstances());
         *             }
         *         });
         */
        NAMING_LOGGER.info("[LISTENER] adding " + serviceInfo.getName() + " with " + clusters + " to listener map");
        List<EventListener> observers = Collections.synchronizedList(new ArrayList<EventListener>());
        observers.add(listener);

        // 往观察者map中塞入
        observers = observerMap.putIfAbsent(ServiceInfo.getKey(serviceInfo.getName(), clusters), observers);
        if (observers != null) {
            // 如果存在，直接塞到observers这个集合中
            observers.add(listener);
        }

        // 触发了一次服务改变的事件
        serviceChanged(serviceInfo);
    }

    /**
     * Remove listener.
     *
     * @param serviceName service name
     * @param clusters    clusters
     * @param listener    listener
     */
    public void removeListener(String serviceName, String clusters, EventListener listener) {

        NAMING_LOGGER.info("[LISTENER] removing " + serviceName + " with " + clusters + " from listener map");

        List<EventListener> observers = observerMap.get(ServiceInfo.getKey(serviceName, clusters));
        if (observers != null) {
            Iterator<EventListener> iter = observers.iterator();
            while (iter.hasNext()) {
                EventListener oldListener = iter.next();
                if (oldListener.equals(listener)) {
                    iter.remove();
                }
            }
            if (observers.isEmpty()) {
                observerMap.remove(ServiceInfo.getKey(serviceName, clusters));
            }
        }
    }

    public boolean isSubscribed(String serviceName, String clusters) {
        return observerMap.containsKey(ServiceInfo.getKey(serviceName, clusters));
    }

    public List<ServiceInfo> getSubscribeServices() {
        List<ServiceInfo> serviceInfos = new ArrayList<ServiceInfo>();
        for (String key : observerMap.keySet()) {
            serviceInfos.add(ServiceInfo.fromKey(key));
        }
        return serviceInfos;
    }

    /**
     * Service changed.
     *
     * @param serviceInfo service info
     */
    public void serviceChanged(ServiceInfo serviceInfo) {
        if (serviceInfo == null) {
            return;
        }

        // “改变队列-”
        changedServices.add(serviceInfo);
    }

    @Override
    public void shutdown() throws NacosException {
        String className = this.getClass().getName();
        NAMING_LOGGER.info("{} do shutdown begin", className);
        ThreadUtils.shutdownThreadPool(executor, NAMING_LOGGER);
        closed = true;
        NAMING_LOGGER.info("{} do shutdown stop", className);
    }

    /**
     * 然后这个EventDispatcher 组件中有个Notifier 的任务会不断从这个队列中获取serviceInfo ，通知那些订阅这个服务的listener
     */
    private class Notifier implements Runnable {

        @Override
        public void run() {
            while (!closed) {

                ServiceInfo serviceInfo = null;
                try {
                    // do Nacos client 每当从server发现与本地注册表数据不一致，则将serviceInfo加入changedServices中
                    serviceInfo = changedServices.poll(5, TimeUnit.MINUTES);
                } catch (Exception ignore) {
                }

                if (serviceInfo == null) {
                    continue;
                }

                // do 从observerMap中拿到相应的监听者，执行监听者的onEvent方法
                try {
                    List<EventListener> listeners = observerMap.get(serviceInfo.getKey());

                    if (!CollectionUtils.isEmpty(listeners)) {
                        for (EventListener listener : listeners) {
                            List<Instance> hosts = Collections.unmodifiableList(serviceInfo.getHosts());
                            listener.onEvent(new NamingEvent(serviceInfo.getName(), serviceInfo.getGroupName(),
                                    serviceInfo.getClusters(), hosts));
                        }
                    }

                } catch (Exception e) {
                    NAMING_LOGGER.error("[NA] notify error for service: " + serviceInfo.getName() + ", clusters: "
                            + serviceInfo.getClusters(), e);
                }
            }
        }
    }
}
