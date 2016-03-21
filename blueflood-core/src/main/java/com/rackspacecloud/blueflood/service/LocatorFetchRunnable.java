/*
 * Copyright 2013 Rackspace
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.rackspacecloud.blueflood.service;

import com.codahale.metrics.Timer;
import com.rackspacecloud.blueflood.io.AstyanaxReader;
import com.rackspacecloud.blueflood.rollup.Granularity;
import com.rackspacecloud.blueflood.rollup.SlotKey;
import com.rackspacecloud.blueflood.threading.SizedExecutorService;
import com.rackspacecloud.blueflood.threading.ThreadPoolSizedExecutorServiceAdapter;
import com.rackspacecloud.blueflood.types.Locator;
import com.rackspacecloud.blueflood.types.Range;
import com.rackspacecloud.blueflood.utils.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * fetches locators for a given slot and feeds a worker queue with rollup work. When those are all done notifies the
 * RollupService that slot can be removed from running.
  */
class LocatorFetchRunnable implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(LocatorFetchRunnable.class);
    private static final int LOCATOR_WAIT_FOR_ALL_SECS = 1000;
    
    private final ExecutorService rollupReadExecutor;
    private final SizedExecutorService rollupWriteExecutor;
    private final ExecutorService enumValidatorExecutor;
    private final SlotKey parentSlotKey;
    private final ScheduleContext scheduleCtx;
    private final long serverTime;
    private static final Timer rollupLocatorExecuteTimer = Metrics.timer(RollupService.class, "Locate and Schedule Rollups for Slot");
    private final AstyanaxReader astyanaxReader;

    private static final boolean enableHistograms = Configuration.getInstance().
            getBooleanProperty(CoreConfig.ENABLE_HISTOGRAMS);

    LocatorFetchRunnable(ScheduleContext scheduleCtx,
                         SlotKey destSlotKey,
                         ExecutorService rollupReadExecutor,
                         ThreadPoolExecutor rollupWriteExecutor,
                         ExecutorService enumValidatorExecutor) {
        this(scheduleCtx, destSlotKey, rollupReadExecutor,
                new ThreadPoolSizedExecutorServiceAdapter(rollupWriteExecutor),
                enumValidatorExecutor);
    }
    LocatorFetchRunnable(ScheduleContext scheduleCtx,
                         SlotKey destSlotKey,
                         ExecutorService rollupReadExecutor,
                         SizedExecutorService rollupWriteExecutor,
                         ExecutorService enumValidatorExecutor) {
        this(scheduleCtx, destSlotKey, rollupReadExecutor, rollupWriteExecutor, enumValidatorExecutor, AstyanaxReader.getInstance());
    }
    LocatorFetchRunnable(ScheduleContext scheduleCtx,
                         SlotKey destSlotKey,
                         ExecutorService rollupReadExecutor,
                         SizedExecutorService rollupWriteExecutor,
                         ExecutorService enumValidatorExecutor,
                         AstyanaxReader astyanaxReader) {

        this.rollupReadExecutor = rollupReadExecutor;
        this.rollupWriteExecutor = rollupWriteExecutor;
        this.parentSlotKey = destSlotKey;
        this.scheduleCtx = scheduleCtx;
        this.serverTime = scheduleCtx.getCurrentTimeMillis();
        this.enumValidatorExecutor = enumValidatorExecutor;
        this.astyanaxReader = astyanaxReader;
    }
    
    public void run() {
        final Timer.Context timerCtx = rollupLocatorExecuteTimer.time();
        final Granularity gran = parentSlotKey.getGranularity();
        final int parentSlot = parentSlotKey.getSlot();
        final int shard = parentSlotKey.getShard();
        final Range parentRange = gran.deriveRange(parentSlot, serverTime);

        try {
            gran.finer();
        } catch (Exception ex) {
            log.error("No finer granularity available than " + gran);
            return;
        }

        if (log.isTraceEnabled())
            log.trace("Getting locators for {} {} @ {}", new Object[]{parentSlotKey, parentRange.toString(), scheduleCtx.getCurrentTimeMillis()});
        // todo: I can see this set becoming a memory hog.  There might be a better way of doing this.
        long waitStart = System.currentTimeMillis();
        int rollCount = 0;

        final RollupExecutionContext executionContext = new RollupExecutionContext(Thread.currentThread());
        final RollupBatchWriter rollupBatchWriter = new RollupBatchWriter(rollupWriteExecutor, executionContext);

        Set<Locator> locators = getLocators(parentSlot, shard, executionContext);

        for (Locator locator : locators) {
            rollCount = processLocator(gran, parentRange, rollCount, executionContext, rollupBatchWriter, locator);
        }
        
        // now wait until ctx is drained. someone needs to be notified.
        drainExecutionContext(waitStart, rollCount, executionContext, rollupBatchWriter);

        timerCtx.stop();
    }

    public void drainExecutionContext(long waitStart, int rollCount, RollupExecutionContext executionContext, RollupBatchWriter rollupBatchWriter) {
        log.debug("Waiting for rollups to finish for " + parentSlotKey);
        while (!executionContext.doneReading() || !executionContext.doneWriting()) {
            if (executionContext.doneReading()) {
                rollupBatchWriter.drainBatch(); // gets any remaining rollups enqueued for put. should be no-op after being called once
            }
            try {
                Thread.currentThread().sleep(LOCATOR_WAIT_FOR_ALL_SECS * 1000);
            } catch (InterruptedException ex) {
                if (log.isTraceEnabled())
                    log.trace("Woken wile waiting for rollups to coalesce for {} {}", parentSlotKey);
            } finally {
                String verb = executionContext.doneReading() ? "writing" : "reading";
                log.debug("Still waiting for rollups to finish {} for {} {}", new Object[] {verb, parentSlotKey, System.currentTimeMillis() - waitStart });
            }
        }
        if (log.isDebugEnabled())
            log.debug("Finished {} rollups for (gran,slot,shard) {} in {}", new Object[] {rollCount, parentSlotKey, System.currentTimeMillis() - waitStart});

        if (executionContext.wasSuccessful()) {
            this.scheduleCtx.clearFromRunning(parentSlotKey);
            log.info("Successful completion of rollups for (gran,slot,shard) {} in {}", new Object[] {parentSlotKey, System.currentTimeMillis() - waitStart});
        } else {
            log.error("Performing BasicRollups for {} failed", parentSlotKey);
            this.scheduleCtx.pushBackToScheduled(parentSlotKey, false);
        }
    }

    public int processLocator(Granularity gran, Range parentRange, int rollCount, RollupExecutionContext executionContext, RollupBatchWriter rollupBatchWriter, Locator locator) {
        if (log.isTraceEnabled())
            log.trace("Rolling up (check,metric,dimension) {} for (gran,slot,shard) {}", locator, parentSlotKey);
        try {
            executeRollupForLocator(gran, parentRange, executionContext, rollupBatchWriter, locator);
            rollCount += 1;
        } catch (Throwable any) {
            // continue on, but log the problem so that we can fix things later.
            executionContext.markUnsuccessful(any);
            executionContext.decrementReadCounter();
            log.error(any.getMessage(), any);
            log.error("BasicRollup failed for {} at {}", parentSlotKey, serverTime);
        }

        if (enableHistograms) {
            // Also, compute histograms. Histograms for > 5 MIN granularity are always computed from 5 MIN histograms.
            rollCount = processHistogramForLocator(gran, parentRange, rollCount, executionContext, rollupBatchWriter, locator);
        }
        return rollCount;
    }

    public int processHistogramForLocator(Granularity gran, Range parentRange, int rollCount, RollupExecutionContext executionContext, RollupBatchWriter rollupBatchWriter, Locator locator) {
        try {
            executeHistogramRollupForLocator(gran, parentRange, executionContext, rollupBatchWriter, locator);
            rollCount += 1;
        } catch (RejectedExecutionException ex) {
            executionContext.markUnsuccessful(ex); // We should ideally only recompute the failed locators alone.
            executionContext.decrementReadCounter();
            log.error("Histogram rollup rejected for {} at {}", parentSlotKey, serverTime);
            log.error("Exception: ", ex);
        } catch (Exception ex) { // do not retrigger rollups when they fail. histograms are fine to be lost.
            executionContext.decrementReadCounter();
            log.error("Histogram rollup rejected for {} at {}", parentSlotKey, serverTime);
            log.error("Exception: ", ex);
        }
        return rollCount;
    }

    public void executeHistogramRollupForLocator(Granularity gran, Range parentRange, RollupExecutionContext executionContext, RollupBatchWriter rollupBatchWriter, Locator locator) {
        executionContext.incrementReadCounter();
        final SingleRollupReadContext singleRollupReadContext = new SingleRollupReadContext(locator,
                parentRange, gran);
        rollupReadExecutor.execute(new HistogramRollupRunnable(executionContext, singleRollupReadContext,
                rollupBatchWriter));
    }

    public void executeRollupForLocator(Granularity gran, Range parentRange, RollupExecutionContext executionContext, RollupBatchWriter rollupBatchWriter, Locator locator) {
        executionContext.incrementReadCounter();
        final SingleRollupReadContext singleRollupReadContext = new SingleRollupReadContext(locator, parentRange, gran);
        RollupRunnable rollupRunnable = new RollupRunnable(executionContext, singleRollupReadContext, rollupBatchWriter, enumValidatorExecutor);
        rollupReadExecutor.execute(rollupRunnable);
    }

    public Set<Locator> getLocators(int parentSlot, int shard, RollupExecutionContext executionContext) {
        Set<Locator> locators = new HashSet<Locator>();

        try {
            // get a list of all locators to rollup for a shard
            locators.addAll(astyanaxReader.getLocatorsToRollup(shard));
        } catch (RuntimeException e) {
            executionContext.markUnsuccessful(e);
            log.error("Failed reading locators for slot: " + parentSlot, e);
        }
        return locators;
    }
}
