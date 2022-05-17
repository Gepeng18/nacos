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

package com.alibaba.nacos.example;

import java.util.Properties;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;

/**
 * Nacos naming example.
 *
 * @author nkorange
 */
public class NamingExample {

    public static void main(String[] args) throws NacosException {

        Properties properties = new Properties();
        properties.setProperty("serverAddr", System.getProperty("serverAddr"));
        properties.setProperty("namespace", System.getProperty("namespace"));

        NamingService naming = NamingFactory.createNamingService(properties);

        // 先注册
        naming.registerInstance("nacos.test.3", "11.11.11.11", 8888, "TEST1");

        naming.registerInstance("nacos.test.3", "2.2.2.2", 9999, "DEFAULT");

        System.out.println(naming.getAllInstances("nacos.test.3"));

        // 再下线
        naming.deregisterInstance("nacos.test.3", "2.2.2.2", 9999, "DEFAULT");

        /**
         * nacos支持两种服务发现方式，一种是直接去nacos服务端拉取某个服务的实例列表，就像eureka那样定时去拉取注册表信息，
         * 另一种是服务订阅的方式，就是订阅某个服务，然后这个服务下面的实例列表一旦发生变化，nacos服务端就会使用udp的方式通知客户端，并将实例列表带过去，
         * 这里是第一种
         */
        System.out.println(naming.getAllInstances("nacos.test.3"));

        /**
         * NamingService的getAllInstances 方法可以作为服务发现获取服务实例列表的方式，
         * 其实nacos还支持订阅方式，通过提供订阅的服务名称与监听器（listener ）就可以了，
         * 然后订阅的服务实例信息一旦发生变化，nacos服务端就会通知你这个client，client收到通知后就会调用你这监听器，执行对应的逻辑。
         */
        naming.subscribe("nacos.test.3", new EventListener() {
            @Override
            public void onEvent(Event event) {
                System.out.println(((NamingEvent) event).getServiceName());
                System.out.println(((NamingEvent) event).getInstances());
            }
        });
    }
}
