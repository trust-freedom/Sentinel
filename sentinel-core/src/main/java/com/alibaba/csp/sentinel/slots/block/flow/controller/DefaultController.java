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
package com.alibaba.csp.sentinel.slots.block.flow.controller;

import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.node.OccupyTimeoutProperty;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.PriorityWaitException;
import com.alibaba.csp.sentinel.slots.block.flow.TrafficShapingController;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * Default throttling controller (immediately reject strategy).
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 */
public class DefaultController implements TrafficShapingController {

    private static final int DEFAULT_AVG_USED_TOKENS = 0;

    private double count; // 阈值
    private int grade; // 阈值类型

    public DefaultController(double count, int grade) {
        this.count = count;
        this.grade = grade;
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }

    /**
     * 快速失败的流控效果中的通过性判断
     * @param node resource node
     * @param acquireCount count to acquire
     * @param prioritized whether the request is prioritized
     * @return
     */
    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        // 获取当前时间窗中已经统计的数据，线程数 或 QPS
        // QPS就是每秒通过的请求数，计算方式：rollingCounterInSecond.pass() / rollingCounterInSecond.getWindowIntervalInSec()
        int curCount = avgUsedTokens(node);
        // 若已经统计的数据与本次请求的数量和 大于 设置的阈值，则返回false，表示没有通过检测
        // 若小于等于阈值，则返回true，表示通过检测
        // 若是QPS，curCount + acquireCount 代表在当前一秒的时间窗口内，已pass请求是curCount个，本次需要pass请求acquireCount个，是否超过阈值？？
        if (curCount + acquireCount > count) {
            // 若阈值类型是QPS，且请求的是否优先为true
            if (prioritized && grade == RuleConstant.FLOW_GRADE_QPS) {
                long currentTime;
                long waitInMs;
                currentTime = TimeUtil.currentTimeMillis();
                waitInMs = node.tryOccupyNext(currentTime, acquireCount, count);
                if (waitInMs < OccupyTimeoutProperty.getOccupyTimeout()) {
                    node.addWaitingRequest(currentTime + waitInMs, acquireCount);
                    node.addOccupiedPass(acquireCount);
                    sleep(waitInMs); // sleep等待

                    // PriorityWaitException indicates that the request will pass after waiting for {@link @waitInMs}.
                    // 会被StatisticsSlot捕获，之后操作和请求pass类似
                    // 参数waitInMs并没有被使用
                    throw new PriorityWaitException(waitInMs);
                }
            }
            return false;
        }
        return true; // 已经统计的数据与本次请求的数量和 低于 设置的阈值，则返回true
    }

    private int avgUsedTokens(Node node) {
        // 若没有选择出node，则说明没有做统计工作，直接返回0
        if (node == null) {
            return DEFAULT_AVG_USED_TOKENS;
        }
        // 若阈值类型为线程数，则直接返回当前的线程数量
        // 若阈值类型为QPS，则返回统计的当前的QPS
        return grade == RuleConstant.FLOW_GRADE_THREAD ? node.curThreadNum() : (int)(node.passQps());
    }

    private void sleep(long timeMillis) {
        try {
            Thread.sleep(timeMillis);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }
}
