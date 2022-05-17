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
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ServiceInfo;
import com.alibaba.nacos.client.monitor.MetricsMonitor;
import com.alibaba.nacos.client.naming.backups.FailoverReactor;
import com.alibaba.nacos.client.naming.beat.BeatInfo;
import com.alibaba.nacos.client.naming.beat.BeatReactor;
import com.alibaba.nacos.client.naming.cache.DiskCache;
import com.alibaba.nacos.client.naming.net.NamingProxy;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.alibaba.nacos.client.naming.utils.UtilAndComs;
import com.alibaba.nacos.common.lifecycle.Closeable;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.common.utils.StringUtils;
import com.alibaba.nacos.common.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static com.alibaba.nacos.client.utils.LogUtils.NAMING_LOGGER;

/**
 * Host reactor.
 *
 * @author xuanyin
 */
public class HostReactor implements Closeable {

    private static final long DEFAULT_DELAY = 1000L;

    private static final long UPDATE_HOLD_INTERVAL = 5000L;

    private final Map<String, ScheduledFuture<?>> futureMap = new HashMap<String, ScheduledFuture<?>>();

    private final Map<String, ServiceInfo> serviceInfoMap;

    private final Map<String, Object> updatingMap;

    private final PushReceiver pushReceiver;

    private final EventDispatcher eventDispatcher;

    private final BeatReactor beatReactor;

    private final NamingProxy serverProxy;

    private final FailoverReactor failoverReactor;

    private final String cacheDir;

    private final ScheduledExecutorService executor;

    public HostReactor(EventDispatcher eventDispatcher, NamingProxy serverProxy, BeatReactor beatReactor,
            String cacheDir) {
        this(eventDispatcher, serverProxy, beatReactor, cacheDir, false, UtilAndComs.DEFAULT_POLLING_THREAD_COUNT);
    }

    public HostReactor(EventDispatcher eventDispatcher, NamingProxy serverProxy, BeatReactor beatReactor,
            String cacheDir, boolean loadCacheAtStart, int pollingThreadCount) {
        // init executorService
        this.executor = new ScheduledThreadPoolExecutor(pollingThreadCount, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                thread.setName("com.alibaba.nacos.client.naming.updater");
                return thread;
            }
        });
        this.eventDispatcher = eventDispatcher;
        this.beatReactor = beatReactor;
        this.serverProxy = serverProxy;
        this.cacheDir = cacheDir;
        if (loadCacheAtStart) {
            this.serviceInfoMap = new ConcurrentHashMap<String, ServiceInfo>(DiskCache.read(this.cacheDir));
        } else {
            this.serviceInfoMap = new ConcurrentHashMap<String, ServiceInfo>(16);
        }

        this.updatingMap = new ConcurrentHashMap<String, Object>();
        this.failoverReactor = new FailoverReactor(this, cacheDir);
        // 创建了一个 PushReceiver，用于UDP通信
        this.pushReceiver = new PushReceiver(this);
    }

    public Map<String, ServiceInfo> getServiceInfoMap() {
        return serviceInfoMap;
    }

    public synchronized ScheduledFuture<?> addTask(UpdateTask task) {
        // 延迟1秒执行
        return executor.schedule(task, DEFAULT_DELAY, TimeUnit.MILLISECONDS);
    }

    /**
     * Process service json.
     *
     * 这里干了三件非常重要的事：
     * 1、serviceInfoMap.put(serviceInfo.getKey(), serviceInfo); + serviceInfo.setJsonFromServer(json);
     *      这就是更新serviceInfo
     * 2、updateBeatInfo(modHosts); 变更心跳信息(因为instance可能变了，所以要变更心跳)
     * 3、eventDispatcher.serviceChanged(serviceInfo); 记录变更事件
     *
     */
    // 分析这个方式之前要达成一个共识，来自于server的数据是最新的数据
    public ServiceInfo processServiceJson(String json) {
        // 将来自于Server的JSON转换为ServiceInfo
        ServiceInfo serviceInfo = JacksonUtils.toObj(json, ServiceInfo.class);
        // 获取注册表中当前服务的ServiceInfo
        ServiceInfo oldService = serviceInfoMap.get(serviceInfo.getKey());
        if (serviceInfo.getHosts() == null || !serviceInfo.validate()) {
            //empty or error push, just ignore
            return oldService;
        }

        boolean changed = false;

        // 若当前注册表中存在当前服务,则想办法将来自于server的数据更新到本地注册表
        if (oldService != null) {

            // 服务器中应该是最新的数据，所以正常情况下，serviceInfo.lastRefTime比oldService要大
            // 这种情况下应该不会出现，为了安全，加一个日志
            if (oldService.getLastRefTime() > serviceInfo.getLastRefTime()) {
                NAMING_LOGGER.warn("out of date data received, old-t: " + oldService.getLastRefTime() + ", new-t: "
                        + serviceInfo.getLastRefTime());
            }

            // 依旧把服务端的数据当作最新的，将来自于Server的serviceInfo替换掉注册表中的当前服务
            serviceInfoMap.put(serviceInfo.getKey(), serviceInfo);

            // 遍历本地注册表中当前服务的所有instance实例
            Map<String, Instance> oldHostMap = new HashMap<String, Instance>(oldService.getHosts().size());
            for (Instance host : oldService.getHosts()) {
                // 将当前遍历的instance主机的ip:port作为key, instance作为value, 写入到一个新的map
                oldHostMap.put(host.toInetAddr(), host);
            }

            Map<String, Instance> newHostMap = new HashMap<String, Instance>(serviceInfo.getHosts().size());
            for (Instance host : serviceInfo.getHosts()) {
                // 将当前遍历的instance主机的ip:port作为key, instance作为value, 写入到一个新的map
                newHostMap.put(host.toInetAddr(), host);
            }

            // 该set集合中存放的是,两个map(oldHostMap5newHostMap)中都有的ip:port,
            // 但它们的instance不相同,此时会将来自于server的instance写入到这个set
            Set<Instance> modHosts = new HashSet<Instance>();
            // 只有newHostMan由存在的instance,即在server端新增的instance
            Set<Instance> newHosts = new HashSet<Instance>();
            // 只有newHostMan由存在的instance,即在server端被删除的instance
            Set<Instance> remvHosts = new HashSet<Instance>();

            List<Map.Entry<String, Instance>> newServiceHosts = new ArrayList<Map.Entry<String, Instance>>(
                    newHostMap.entrySet());
            // 遍历来自于server的主机
            for (Map.Entry<String, Instance> entry : newServiceHosts) {
                Instance host = entry.getValue();
                // key : ip+port
                String key = entry.getKey();
                // 在注册表中存在该ip:port,但这两个instance又不同,则将这个instance写入到modHosts
                if (oldHostMap.containsKey(key) && !StringUtils
                        .equals(host.toString(), oldHostMap.get(key).toString())) {
                    modHosts.add(host);
                    continue;
                }

                //若注册表中不存在该ip:port,说明该主机是新增的，则将其写入到newHosts
                if (!oldHostMap.containsKey(key)) {
                    newHosts.add(host);
                }
            }

            // 遍历来自于本地注册表的主机
            for (Map.Entry<String, Instance> entry : oldHostMap.entrySet()) {
                Instance host = entry.getValue();
                String key = entry.getKey();
                if (newHostMap.containsKey(key)) {
                    continue;
                }

                // 注册表中存在,但来自于server的serviceInfo中不存在,// 说明这个instance被干掉了,将其写入到remvHosts
                if (!newHostMap.containsKey(key)) {
                    remvHosts.add(host);
                }

            }

            if (newHosts.size() > 0) {
                changed = true;
                NAMING_LOGGER.info("new ips(" + newHosts.size() + ") service: " + serviceInfo.getKey() + " -> "
                        + JacksonUtils.toJson(newHosts));
            }

            if (remvHosts.size() > 0) {
                changed = true;
                NAMING_LOGGER.info("removed ips(" + remvHosts.size() + ") service: " + serviceInfo.getKey() + " -> "
                        + JacksonUtils.toJson(remvHosts));
            }

            if (modHosts.size() > 0) {
                changed = true;
                // 变更心跳信息 beatInfo
                updateBeatInfo(modHosts);
                NAMING_LOGGER.info("modified ips(" + modHosts.size() + ") service: " + serviceInfo.getKey() + " -> "
                        + JacksonUtils.toJson(modHosts));
            }

            serviceInfo.setJsonFromServer(json);

            // 只要发生了变更,就将这个发生变更的serviceInfo记录到一个缓存队列
            if (newHosts.size() > 0 || remvHosts.size() > 0 || modHosts.size() > 0) {
                eventDispatcher.serviceChanged(serviceInfo);
                DiskCache.write(serviceInfo, cacheDir);
            }

        } else {
            // 若本地注册表中就没有当前服务,则直接将来自于server的serviceInfo写入到注册表
            changed = true;
            NAMING_LOGGER.info("init new ips(" + serviceInfo.ipCount() + ") service: " + serviceInfo.getKey() + " -> "
                    + JacksonUtils.toJson(serviceInfo.getHosts()));
            // 将来自于server的serviceInfo写入到注册表
            serviceInfoMap.put(serviceInfo.getKey(), serviceInfo);
            // 将这个发生变更的serviceInfo记录到一个缓存队列
            eventDispatcher.serviceChanged(serviceInfo);
            serviceInfo.setJsonFromServer(json);
            DiskCache.write(serviceInfo, cacheDir);
        }

        MetricsMonitor.getServiceInfoMapSizeMonitor().set(serviceInfoMap.size());

        if (changed) {
            NAMING_LOGGER.info("current ips:(" + serviceInfo.ipCount() + ") service: " + serviceInfo.getKey() + " -> "
                    + JacksonUtils.toJson(serviceInfo.getHosts()));
        }

        return serviceInfo;
    }

    private void updateBeatInfo(Set<Instance> modHosts) {
        for (Instance instance : modHosts) {
            String key = beatReactor.buildKey(instance.getServiceName(), instance.getIp(), instance.getPort());
            // 老的instance存在，且是临时节点，则
            if (beatReactor.dom2Beat.containsKey(key) && instance.isEphemeral()) {
                // 构建新的beatInfo
                BeatInfo beatInfo = beatReactor.buildBeatInfo(instance);
                // 发送心跳(维持联系+如果没有注册，则注册)
                beatReactor.addBeatInfo(instance.getServiceName(), beatInfo);
            }
        }
    }

    private ServiceInfo getServiceInfo0(String serviceName, String clusters) {

        String key = ServiceInfo.getKey(serviceName, clusters);

        // serviceInfoMap即为Client端的本地注册表,
        // key: groupId@@微服务名称@@clusters名称 ,value 为 ServiceInfo
        return serviceInfoMap.get(key);
    }

    public ServiceInfo getServiceInfoDirectlyFromServer(final String serviceName, final String clusters)
            throws NacosException {
        // 这里直接是调用了serverProxy组件的queryList 方法，需要注意的是这个udp端口是0 ，因为咱们这里是不订阅的，这个upd是给订阅的接收通知用的
        String result = serverProxy.queryList(serviceName, clusters, 0, false);
        if (StringUtils.isNotEmpty(result)) {
            return JacksonUtils.toObj(result, ServiceInfo.class);
        }
        return null;
    }

    /**
     * 1、从当前client的本地注册表中获取当前服务
     * 2、如果本地沒有这个服务，就创建空服务添加到本地注册表中，并且从Server拉取 (/v1/ns/instance/list)
     * 3、如果发现本地该服务正在更新，则等一会后直接从本地注册表中获即可
     * 4、启动一个定时任务，定期更新本地注册表,从Server拉取  (/v1/ns/instance/list)
     *
     * 主动拉取：
     * 主要就是看看本地有没有这个serviceInfo ，如果没有的话，就创建一个，并且立马向服务端拉取这个服务实例信息，
     * 另外带着订阅。然后就是将这个订阅搞个任务出来，定时的去服务端拉拉看看，这个任务就是防止某个nacos服务端实例挂了
     * 导致不能及时通知了，它时不时的去服务端刷新下订阅信息，就算是某个nacos 服务挂掉了，它也可以请求其他的nacos服务继续拉取实例，
     * 同时也告诉下nacos 服务端我这个客户端还活着，维护着一个连接那种感觉。
     */
    public ServiceInfo getServiceInfo(final String serviceName, final String clusters) {

        NAMING_LOGGER.debug("failover-mode: " + failoverReactor.isFailoverSwitch());
        // 构建key,其格式为 groupId@@微服务名称@@cLusters名称
        String key = ServiceInfo.getKey(serviceName, clusters);
        if (failoverReactor.isFailoverSwitch()) {
            return failoverReactor.getService(key);
        }

        // do 从当前client的本地注册表中获取当前服务
        ServiceInfo serviceObj = getServiceInfo0(serviceName, clusters);

        // 第一次时，本地注册表中没有该服务
        if (null == serviceObj) {
            // 创建一个空的服务(没有任何提供者实例instance的ServiceInfo)
            serviceObj = new ServiceInfo(serviceName, clusters);
            serviceInfoMap.put(serviceObj.getKey(), serviceObj);

            // updatingMap是一个临时缓存,其主要是使用这个缓存map的key,
            // 使用map的key不能重复这个特性
            // 只要有服务名称出现在这个缓存map中,就表示当前这个服务正在被更新
            // 准备要更新serviceName的服务了,就先将其名称写入到这个临时缓存map
            updatingMap.put(serviceName, new Object());
            // do 更新本地注册表中的serviceName的服务（立即发送请求去客户端端获取）
            updateServiceNow(serviceName, clusters);
            // 更新完毕,将该serviceName服务从临时缓存map中干掉
            updatingMap.remove(serviceName);

        } else if (updatingMap.containsKey(serviceName)) {
            // 若当前注册表中已经有了这个服务,那么查看一下临时缓存map中
            // 是否存在该服务。若存在,则说明这个服务正在被更新,所以本次操作先等待一会
            if (UPDATE_HOLD_INTERVAL > 0) {
                // hold a moment waiting for update finish
                synchronized (serviceObj) {
                    try {
                        serviceObj.wait(UPDATE_HOLD_INTERVAL);
                    } catch (InterruptedException e) {
                        NAMING_LOGGER
                                .error("[getServiceInfo] serviceName:" + serviceName + ", clusters:" + clusters, e);
                    }
                }
            }
        }

        // do 启动一个定时任务，定时更新本地注册表中的当前服务
        scheduleUpdateIfAbsent(serviceName, clusters);

        return serviceInfoMap.get(serviceObj.getKey());
    }

    private void updateServiceNow(String serviceName, String clusters) {
        try {
            updateService(serviceName, clusters);
        } catch (NacosException e) {
            NAMING_LOGGER.error("[NA] failed to update serviceName: " + serviceName, e);
        }
    }

    /**
     * Schedule update if absent.
     *
     * @param serviceName service name
     * @param clusters    clusters
     */
    public void scheduleUpdateIfAbsent(String serviceName, String clusters) {
        // futureMap是-个缓存map,key 是 groupId@@微服务名称@@clusters
        // value是一个定时异步操作对象
        // 这种结构称之为 DCL，该结构是为了避免在并发情况下多线程重复写入数据
        if (futureMap.get(ServiceInfo.getKey(serviceName, clusters)) != null) {
            return;
        }

        synchronized (futureMap) {
            if (futureMap.get(ServiceInfo.getKey(serviceName, clusters)) != null) {
                return;
            }

            // 创建一个定时异步操作对象，并启动这个定时任务
            ScheduledFuture<?> future = addTask(new UpdateTask(serviceName, clusters));
            // 将这个定时异步操作对象写入到缓存map
            futureMap.put(ServiceInfo.getKey(serviceName, clusters), future);
        }
    }

    /**
     * Update service now.
     *
     * @param serviceName service name
     * @param clusters    clusters
     */
    public void updateService(String serviceName, String clusters) throws NacosException {
        // 从本地注册表获取当前服务
        ServiceInfo oldService = getServiceInfo0(serviceName, clusters);
        try {

            // 向server提交一个get请求,获取服务ServiceInfo(使用UDP吗，我看获取了UDP端口)
            // 获取的返回值以json串的形式出现的
            String result = serverProxy.queryList(serviceName, clusters, pushReceiver.getUdpPort(), false);

            if (StringUtils.isNotEmpty(result)) {
                // 将来自于server的serviceinfo更新到本地注册表
                processServiceJson(result);
            }
        } finally {
            if (oldService != null) {
                synchronized (oldService) {
                    oldService.notifyAll();
                }
            }
        }
    }

    /**
     * Refresh only.
     *
     * @param serviceName service name
     * @param clusters    cluster
     */
    public void refreshOnly(String serviceName, String clusters) {
        try {
            serverProxy.queryList(serviceName, clusters, pushReceiver.getUdpPort(), false);
        } catch (Exception e) {
            NAMING_LOGGER.error("[NA] failed to update serviceName: " + serviceName, e);
        }
    }

    @Override
    public void shutdown() throws NacosException {
        String className = this.getClass().getName();
        NAMING_LOGGER.info("{} do shutdown begin", className);
        ThreadUtils.shutdownThreadPool(executor, NAMING_LOGGER);
        pushReceiver.shutdown();
        failoverReactor.shutdown();
        NAMING_LOGGER.info("{} do shutdown stop", className);
    }

    public class UpdateTask implements Runnable {

        long lastRefTime = Long.MAX_VALUE;

        private final String clusters;

        private final String serviceName;

        /**
         * the fail situation. 1:can't connect to server 2:serviceInfo's hosts is empty
         */
        private int failCount = 0;

        public UpdateTask(String serviceName, String clusters) {
            this.serviceName = serviceName;
            this.clusters = clusters;
        }

        private void incFailCount() {
            int limit = 6;
            if (failCount == limit) {
                return;
            }
            failCount++;
        }

        private void resetFailCount() {
            failCount = 0;
        }

        @Override
        public void run() {
            long delayTime = DEFAULT_DELAY;

            try {
                // 从本地注册表中获取当前服务
                ServiceInfo serviceObj = serviceInfoMap.get(ServiceInfo.getKey(serviceName, clusters));

                // 若本地注册表中不存在该服务,则从server获取到后,更新到本地注册表
                if (serviceObj == null) {
                    // 从server获取当前服务,并更新到本地注册表
                    updateService(serviceName, clusters);
                    return;
                }

                // 处理本地注册表中存在当前服务的情况，serviceobj.getLastRefTime()获取到的是当前服务最后被访问的时间,这个时间
                // 是来自于本地注册表的,其记录的是所有提供这个服务的instance中最后一个instance被访问的时间
                // 缓存中的lastRefTime 记录的是当前instance最后被访问的时间
                if (serviceObj.getLastRefTime() <= lastRefTime) {
                    // 若1)时间小干2)时间,说明当前注册表应该更新的
                    updateService(serviceName, clusters);
                    serviceObj = serviceInfoMap.get(ServiceInfo.getKey(serviceName, clusters));
                } else {
                    // if serviceName already updated by push, we should not override it
                    // since the push data may be different from pull through force push
                    // 这里只是获取，但是没有接受返回值，这个方法就是告诉服务端要订阅哪个服务
                    refreshOnly(serviceName, clusters);
                }

                // 将来自于注册表的这个最后访问时间更新到当前client的缓存
                lastRefTime = serviceObj.getLastRefTime();

                // 如果eventDispatcher 组件没有订阅这个服务并且 futureMap（任务集合）里面没有这个的话，就说明被任务被停了，
                // 这个任务直接return就可以
                if (!eventDispatcher.isSubscribed(serviceName, clusters) && !futureMap
                        .containsKey(ServiceInfo.getKey(serviceName, clusters))) {
                    // abort the update task
                    NAMING_LOGGER.info("update task is stopped, service:" + serviceName + ", clusters:" + clusters);
                    return;
                }
                if (CollectionUtils.isEmpty(serviceObj.getHosts())) {
                    incFailCount();
                    return;
                }
                // 计算下延迟时间，然后放到调度线程池中执行，普通情况延迟10s，失败的话就多延迟会，但是不会超过60s。
                delayTime = serviceObj.getCacheMillis();
                resetFailCount();
            } catch (Throwable e) {
                incFailCount();
                NAMING_LOGGER.warn("[NA] failed to update serviceName: " + serviceName, e);
            } finally {
                // 开启下一次定时任务
                executor.schedule(this, Math.min(delayTime << failCount, DEFAULT_DELAY * 60), TimeUnit.MILLISECONDS);
            }
        }
    }
}
