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

package com.alibaba.nacos.common.task.engine;

import com.alibaba.nacos.common.task.AbstractDelayTask;
import com.alibaba.nacos.common.task.NacosTaskProcessor;
import org.slf4j.Logger;

import java.util.Collection;

/**
 * Nacos delay task execute engine.
 *
 * @author xiweng.yy
 */
public class NacosDelayTaskExecuteEngine extends AbstractNacosTaskExecuteEngine<AbstractDelayTask> {

    public NacosDelayTaskExecuteEngine(String name) {
        super(name);
    }

    public NacosDelayTaskExecuteEngine(String name, Logger logger) {
        super(name, logger);
    }

    public NacosDelayTaskExecuteEngine(String name, Logger logger, long processInterval) {
        super(name, logger, processInterval);
    }

    public NacosDelayTaskExecuteEngine(String name, int initCapacity, Logger logger) {
        super(name, initCapacity, logger);
    }

    public NacosDelayTaskExecuteEngine(String name, int initCapacity, Logger logger, long processInterval) {
        super(name, initCapacity, logger, processInterval);
    }

    @Override
    protected void processTasks() {
        // 获取所有的task
        Collection<Object> keys = getAllTaskKeys();
        // 遍历
        for (Object taskKey : keys) {
            //获取任务
            AbstractDelayTask task = removeTask(taskKey);
            if (null == task) {
                continue;
            }
            // 获取processor
            NacosTaskProcessor processor = getProcessor(taskKey);
            if (null == processor) {
                getEngineLog().error("processor not found for task, so discarded. " + task);
                continue;
            }
            try {
                // ReAdd task if process failed
                // 如果处理失败的话，就进行重试
                // 交由DistroHttpDelayTaskProcessor处理
                if (!processor.process(task)) {
                    retryFailedTask(taskKey, task);
                }
            } catch (Throwable e) {
                getEngineLog().error("Nacos task execute error : " + e.toString(), e);
                // 重试
                retryFailedTask(taskKey, task);
            }
        }
    }

    // 添加任务
    @Override
    public void addTask(Object key, AbstractDelayTask newTask) {
        lock.lock();
        try {
            //先获取
            AbstractDelayTask existTask = tasks.get(key);
            if (null != existTask) {
                // 如果存在的话，就合并任务
                newTask.merge(existTask);
            }
            // 放入map中去
            tasks.put(key, newTask);
        } finally {
            lock.unlock();
        }
    }

    private void retryFailedTask(Object key, AbstractDelayTask task) {
        task.setLastProcessTime(System.currentTimeMillis());
        addTask(key, task);
    }
}
