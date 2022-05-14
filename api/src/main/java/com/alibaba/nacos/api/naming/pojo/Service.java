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

package com.alibaba.nacos.api.naming.pojo;

import java.util.HashMap;
import java.util.Map;

/**
 * Service of Nacos.
 *
 * <p>We introduce a 'service --> cluster --> instance' model, in which service stores a list of clusters, which contains a
 * list of instances.
 *
 * <p>Typically we put some unique properties between instances to service level.
 *
 * @author nkorange
 */
public class Service {

    /**
     * service name.
     */
    private String name;

    /**
     * protect threshold.
     * 保护阈值，数值处于0-1之间，表示健康实例占所有实例的比例
     * 保护方式不同：
     *      Eureka:一旦健康实例数量小于阅值,则不再从注册表中清除不健康的实例
     *      Nacos:如果健康实例数量大于阅值,则消费者调用到的都是健康实例。一旦健康实例数量小于阔值,则消费者会从所有实例中进行选择调用,有可能会调用到不健康实例
     *          考虑点：如果健康实例数量很少，且只调用这些健康实例，那么这些实例压力就很大，所以就牺牲消费者的权益，让消费者重试来解决
     *                 保证健康的实例不会被压崩溃
     *  保护范围不同：
     *      Eureka:这个阅值针对的是所有服务的实例
     *      Nacos:这个阅值针对的是当前Service中的服务实例
     */
    private float protectThreshold = 0.0F;

    /**
     * application name of this service.
     */
    private String appName;

    /**
     * Service group to classify services into different sets.
     */
    private String groupName;

    private Map<String, String> metadata = new HashMap<String, String>();

    public Service() {
    }

    public Service(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public float getProtectThreshold() {
        return protectThreshold;
    }

    public void setProtectThreshold(float protectThreshold) {
        this.protectThreshold = protectThreshold;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public void addMetadata(String key, String value) {
        this.metadata.put(key, value);
    }

    @Override
    public String toString() {
        return "Service{" + "name='" + name + '\'' + ", protectThreshold=" + protectThreshold + ", appName='" + appName
                + '\'' + ", groupName='" + groupName + '\'' + ", metadata=" + metadata + '}';
    }
}
