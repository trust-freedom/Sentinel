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
package com.alibaba.csp.sentinel.slots.statistic.metric;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.csp.sentinel.config.SentinelConfig;
import com.alibaba.csp.sentinel.node.metric.MetricNode;
import com.alibaba.csp.sentinel.slots.statistic.MetricEvent;
import com.alibaba.csp.sentinel.slots.statistic.base.LeapArray;
import com.alibaba.csp.sentinel.slots.statistic.data.MetricBucket;
import com.alibaba.csp.sentinel.slots.statistic.base.WindowWrap;
import com.alibaba.csp.sentinel.slots.statistic.metric.occupy.OccupiableBucketLeapArray;
import com.alibaba.csp.sentinel.util.function.Predicate;

/**
 * The basic metric class in Sentinel using a {@link BucketLeapArray} internal.
 * 一个基本的统计类，内部使用了 BucketLeapArray
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 */
// 这是一个使用数组保存数据的计量器类
public class ArrayMetric implements Metric {

    // 数据就保存在这个LeapArray中
    // LeapArray数组中的元素是WindowWrap
    // MetricBucket表示样本窗口WindowWrap中保存的数据类型
    private final LeapArray<MetricBucket> data;

    public ArrayMetric(int sampleCount, int intervalInMs) {
        this.data = new OccupiableBucketLeapArray(sampleCount, intervalInMs);
    }

    public ArrayMetric(int sampleCount, int intervalInMs, boolean enableOccupy) {
        if (enableOccupy) {
            this.data = new OccupiableBucketLeapArray(sampleCount, intervalInMs);
        } else {
            this.data = new BucketLeapArray(sampleCount, intervalInMs);
        }
    }

    /**
     * For unit test.
     */
    public ArrayMetric(LeapArray<MetricBucket> array) {
        this.data = array;
    }

    @Override
    public long success() {
        data.currentWindow();
        long success = 0;

        List<MetricBucket> list = data.values();
        for (MetricBucket window : list) {
            success += window.success();
        }
        return success;
    }

    @Override
    public long maxSuccess() {
        data.currentWindow();
        long success = 0;

        List<MetricBucket> list = data.values();
        for (MetricBucket window : list) {
            if (window.success() > success) {
                success = window.success();
            }
        }
        return Math.max(success, 1);
    }

    @Override
    public long exception() {
        data.currentWindow();
        long exception = 0;
        List<MetricBucket> list = data.values();
        for (MetricBucket window : list) {
            exception += window.exception();
        }
        return exception;
    }

    @Override
    public long block() {
        data.currentWindow();
        long block = 0;
        List<MetricBucket> list = data.values();
        for (MetricBucket window : list) {
            block += window.block();
        }
        return block;
    }

    @Override
    public long pass() {
        // 原本是 获取当前时间点所在的样本窗口
        // 更新array中当前时间点所在的样本窗口实例中的数据
        data.currentWindow();
        long pass = 0;
        // 将当前时间窗口中的所有样本窗口统计的value记录到result中
        // 遍历所有样本窗口，若当前遍历实例为空 或 已经过时 的不会统计
        // 即如果当前时间窗口内有2个样本窗口，但都是过时的历史数据
        // 此时尽量请求查看pass落在第二个样本窗口，而第一个样本窗口的数据已是不知道多久前过时的数据，就不会统计在内
        List<MetricBucket> list = data.values();

        // 将list中所有pass维度的统计数据取出并求和
        for (MetricBucket window : list) {
            pass += window.pass();
        }
        return pass;
    }

    @Override
    public long occupiedPass() {
        data.currentWindow();
        long pass = 0;
        List<MetricBucket> list = data.values();
        for (MetricBucket window : list) {
            pass += window.occupiedPass();
        }
        return pass;
    }

    @Override
    public long rt() {
        data.currentWindow();
        long rt = 0;
        List<MetricBucket> list = data.values();
        for (MetricBucket window : list) {
            rt += window.rt();
        }
        return rt;
    }

    @Override
    public long minRt() {
        data.currentWindow();
        long rt = SentinelConfig.statisticMaxRt();
        List<MetricBucket> list = data.values();
        for (MetricBucket window : list) {
            if (window.minRt() < rt) {
                rt = window.minRt();
            }
        }

        return Math.max(1, rt);
    }

    @Override
    public List<MetricNode> details() {
        List<MetricNode> details = new ArrayList<>();
        data.currentWindow();
        List<WindowWrap<MetricBucket>> list = data.list();
        for (WindowWrap<MetricBucket> window : list) {
            if (window == null) {
                continue;
            }

            details.add(fromBucket(window));
        }

        return details;
    }

    @Override
    public List<MetricNode> detailsOnCondition(Predicate<Long> timePredicate) {
        List<MetricNode> details = new ArrayList<>();
        data.currentWindow();
        List<WindowWrap<MetricBucket>> list = data.list();
        for (WindowWrap<MetricBucket> window : list) {
            if (window == null) {
                continue;
            }
            if (timePredicate != null && !timePredicate.test(window.windowStart())) {
                continue;
            }

            details.add(fromBucket(window));
        }

        return details;
    }

    private MetricNode fromBucket(WindowWrap<MetricBucket> wrap) {
        MetricNode node = new MetricNode();
        node.setBlockQps(wrap.value().block());
        node.setExceptionQps(wrap.value().exception());
        node.setPassQps(wrap.value().pass());
        long successQps = wrap.value().success();
        node.setSuccessQps(successQps);
        if (successQps != 0) {
            node.setRt(wrap.value().rt() / successQps);
        } else {
            node.setRt(wrap.value().rt());
        }
        node.setTimestamp(wrap.windowStart());
        node.setOccupiedPassQps(wrap.value().occupiedPass());
        return node;
    }

    @Override
    public MetricBucket[] windows() {
        data.currentWindow();
        return data.values().toArray(new MetricBucket[0]);
    }

    @Override
    public void addException(int count) {
        WindowWrap<MetricBucket> wrap = data.currentWindow();
        wrap.value().addException(count);
    }

    @Override
    public void addBlock(int count) {
        WindowWrap<MetricBucket> wrap = data.currentWindow();
        wrap.value().addBlock(count);
    }

    @Override
    public void addWaiting(long time, int acquireCount) {
        data.addWaiting(time, acquireCount);
    }

    @Override
    public void addOccupiedPass(int acquireCount) {
        WindowWrap<MetricBucket> wrap = data.currentWindow();
        wrap.value().addOccupiedPass(acquireCount);
    }

    @Override
    public void addSuccess(int count) {
        WindowWrap<MetricBucket> wrap = data.currentWindow();
        wrap.value().addSuccess(count);
    }

    @Override
    public void addPass(int count) {
        // 获取当前时间点所在的样本窗口
        // currentWindow(TimeUtil.currentTimeMillis())
        WindowWrap<MetricBucket> wrap = data.currentWindow();
        // 将当前请求的计数量添加到当前样本窗口的统计数据中
        // wrap.value()是MetricBucket，即样本窗口中的统计数据类型
        // addPass是add(MetricEvent.PASS, n)
        wrap.value().addPass(count);
    }

    @Override
    public void addRT(long rt) {
        WindowWrap<MetricBucket> wrap = data.currentWindow();
        wrap.value().addRT(rt);
    }

    @Override
    public void debug() {
        data.debug(System.currentTimeMillis());
    }

    @Override
    public long previousWindowBlock() {
        data.currentWindow();
        WindowWrap<MetricBucket> wrap = data.getPreviousWindow();
        if (wrap == null) {
            return 0;
        }
        return wrap.value().block();
    }

    @Override
    public long previousWindowPass() {
        data.currentWindow();
        WindowWrap<MetricBucket> wrap = data.getPreviousWindow();
        if (wrap == null) {
            return 0;
        }
        return wrap.value().pass();
    }

    public void add(MetricEvent event, long count) {
        data.currentWindow().value().add(event, count);
    }

    public long getCurrentCount(MetricEvent event) {
        return data.currentWindow().value().get(event);
    }

    /**
     * Get total sum for provided event in {@code intervalInSec}.
     *
     * @param event event to calculate
     * @return total sum for event
     */
    public long getSum(MetricEvent event) {
        data.currentWindow();
        long sum = 0;

        List<MetricBucket> buckets = data.values();
        for (MetricBucket bucket : buckets) {
            sum += bucket.get(event);
        }
        return sum;
    }

    /**
     * Get average count for provided event per second.
     *
     * @param event event to calculate
     * @return average count per second for event
     */
    public double getAvg(MetricEvent event) {
        return getSum(event) / data.getIntervalInSecond();
    }

    @Override
    public long getWindowPass(long timeMillis) {
        MetricBucket bucket = data.getWindowValue(timeMillis);
        if (bucket == null) {
            return 0L;
        }
        return bucket.pass();
    }

    @Override
    public long waiting() {
        return data.currentWaiting();
    }

    @Override
    public double getWindowIntervalInSec() {
        return data.getIntervalInSecond();
    }

    @Override
    public int getSampleCount() {
        return data.getSampleCount();
    }
}
