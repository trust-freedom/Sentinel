/*
 * Copyright 1999-2019 Alibaba Group Holding Ltd.
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
package com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.statistic.base.LeapArray;
import com.alibaba.csp.sentinel.slots.statistic.base.WindowWrap;
import com.alibaba.csp.sentinel.util.AssertUtil;

import static com.alibaba.csp.sentinel.slots.block.RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT;
import static com.alibaba.csp.sentinel.slots.block.RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO;

/**
 * @author Eric Zhao
 * @since 1.8.0
 */
public class ExceptionCircuitBreaker extends AbstractCircuitBreaker {

    private final int strategy;
    private final int minRequestAmount;
    private final double threshold;

    private final LeapArray<SimpleErrorCounter> stat;

    public ExceptionCircuitBreaker(DegradeRule rule) {
        this(rule, new SimpleErrorCounterLeapArray(1, rule.getStatIntervalMs()));
    }

    ExceptionCircuitBreaker(DegradeRule rule, LeapArray<SimpleErrorCounter> stat) {
        super(rule);
        this.strategy = rule.getGrade();
        boolean modeOk = strategy == DEGRADE_GRADE_EXCEPTION_RATIO || strategy == DEGRADE_GRADE_EXCEPTION_COUNT;
        AssertUtil.isTrue(modeOk, "rule strategy should be error-ratio or error-count");
        AssertUtil.notNull(stat, "stat cannot be null");
        this.minRequestAmount = rule.getMinRequestAmount();
        this.threshold = rule.getCount();
        this.stat = stat;
    }

    @Override
    protected void resetStat() {
        // Reset current bucket (bucket count = 1).
        stat.currentWindow().value().reset();
    }

    @Override
    public void onRequestComplete(Context context) {
        Entry entry = context.getCurEntry();
        if (entry == null) {
            return;
        }
        // 当前Entry的错误信息
        Throwable error = entry.getError();
        // 当前时间窗口的错误统计
        SimpleErrorCounter counter = stat.currentWindow().value();
        // 若当前Entry有错误，ErrorCounter错误数+1
        if (error != null) {
            counter.getErrorCount().add(1);
        }
        // ErrorCounter总数+1
        counter.getTotalCount().add(1);

        // 处理断路器状态
        handleStateChangeWhenThresholdExceeded(error);
    }

    private void handleStateChangeWhenThresholdExceeded(Throwable error) {
        // 当前OPEN状态，直接返回
        if (currentState.get() == State.OPEN) {
            return;
        }

        // 当前HALF_OPEN状态
        if (currentState.get() == State.HALF_OPEN) {
            // In detecting request
            // 当前是检测请求，没有错误，HALF_OPEN->CLOSED
            if (error == null) {
                fromHalfOpenToClose();
            } else { // 有错误，HALF_OPEN->OPEN
                fromHalfOpenToOpen(1.0d);
            }
            return;
        }

        // 下面操作根据统计值判断是否将状态置为OPEN，即开启熔断
        // 获取整个滑动窗口的聚合值列表
        List<SimpleErrorCounter> counters = stat.values(); // SimpleErrorCounter
        long errCount = 0;
        long totalCount = 0;
        for (SimpleErrorCounter counter : counters) {
            errCount += counter.errorCount.sum();
            totalCount += counter.totalCount.sum();
        }
        // 没有达到最小请求数量
        if (totalCount < minRequestAmount) {
            return;
        }
        double curCount = errCount;
        // 错误比例策略
        if (strategy == DEGRADE_GRADE_EXCEPTION_RATIO) {
            // Use errorRatio
            curCount = errCount * 1.0d / totalCount;
        } // else 错误数策略
        // 超过阈值，则将状态置为OPEN
        if (curCount > threshold) {
            transformToOpen(curCount);
        }
    }

    static class SimpleErrorCounter {
        private LongAdder errorCount;
        private LongAdder totalCount;

        public SimpleErrorCounter() {
            this.errorCount = new LongAdder();
            this.totalCount = new LongAdder();
        }

        public LongAdder getErrorCount() {
            return errorCount;
        }

        public LongAdder getTotalCount() {
            return totalCount;
        }

        public SimpleErrorCounter reset() {
            errorCount.reset();
            totalCount.reset();
            return this;
        }

        @Override
        public String toString() {
            return "SimpleErrorCounter{" +
                "errorCount=" + errorCount +
                ", totalCount=" + totalCount +
                '}';
        }
    }

    static class SimpleErrorCounterLeapArray extends LeapArray<SimpleErrorCounter> {

        public SimpleErrorCounterLeapArray(int sampleCount, int intervalInMs) {
            super(sampleCount, intervalInMs);
        }

        @Override
        public SimpleErrorCounter newEmptyBucket(long timeMillis) {
            return new SimpleErrorCounter();
        }

        @Override
        protected WindowWrap<SimpleErrorCounter> resetWindowTo(WindowWrap<SimpleErrorCounter> w, long startTime) {
            // Update the start time and reset value.
            w.resetTo(startTime);
            w.value().reset();
            return w;
        }
    }
}
