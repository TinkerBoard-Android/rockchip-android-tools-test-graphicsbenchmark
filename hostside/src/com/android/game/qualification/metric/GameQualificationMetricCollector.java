/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.game.qualification.metric;

import com.android.game.qualification.ApkInfo;
import com.android.game.qualification.proto.ResultDataProto;

import com.android.tradefed.device.metric.BaseDeviceMetricCollector;
import com.android.tradefed.device.metric.DeviceMetricData;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.DataType;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.metrics.proto.MetricMeasurement.Directionality;
import com.android.tradefed.metrics.proto.MetricMeasurement.Measurements;
import com.android.tradefed.config.Option;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.InputStreamSource;

import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.ArrayList;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/** A {@link ScheduledDeviceMetricCollector} to collect graphics benchmarking stats at regular intervals. */
public class GameQualificationMetricCollector extends BaseDeviceMetricCollector {
    private long mLatestSeen = 0;
    private ApkInfo mTestApk;
    private ResultDataProto.Result mDeviceResultData;
    private long mVSyncPeriod = 0;
    private ArrayList<Long> mElapsedTimes;
    private ITestDevice mDevice;
    private int mRunCount = 0;
    private boolean mFirstLoop;

    @Option(
        name = "fixed-schedule-rate",
        description = "Schedule the timetask as a fixed schedule rate"
    )
    private boolean mFixedScheduleRate = false;

    @Option(
        name = "interval",
        description = "the interval between two tasks being scheduled",
        isTimeVal = true
    )
    private long mIntervalMs = 1 * 1000L;

    private Timer mTimer;

    public void setApkInfo(ApkInfo apk) {
        mTestApk = apk;
    }

    public void setDeviceResultData(ResultDataProto.Result resultData) {
        mDeviceResultData = resultData;
    }

    public void setDevice(ITestDevice device) {
        mDevice = device;
    }

    @Override
    public final void onTestRunStart(DeviceMetricData runData) {
        CLog.v("Test run started on device %s.", mDevice);

        mElapsedTimes = new ArrayList<>();
        mLatestSeen = 0;
        mFirstLoop = true;

        onStart(runData);
        mTimer = new Timer();
        TimerTask timerTask =
                new TimerTask() {
                    @Override
                    public void run() {
                        try {
                            collect(runData);
                        } catch (InterruptedException e) {
                            mTimer.cancel();
                            Thread.currentThread().interrupt();
                            CLog.e("Interrupted exception thrown from task: %s", e);
                        }
                    }
                };

        if (mFixedScheduleRate) {
            mTimer.scheduleAtFixedRate(timerTask, 0, mIntervalMs);
        } else {
            mTimer.schedule(timerTask, 0, mIntervalMs);
        }
    }

    @Override
    public final void onTestRunEnd(DeviceMetricData runData, Map<String, Metric> currentRunMetrics) {
        if (mTimer != null) {
            mTimer.cancel();
            mTimer.purge();
        }
        onEnd(runData);
        CLog.d("onTestRunEnd");
    }


    /**
     * Task periodically & asynchronously run during the test running.
     *
     * @param runData the {@link DeviceMetricData} where to put metrics.
     * @throws InterruptedException
     */
    private void collect(DeviceMetricData runData) throws InterruptedException {
        try {

            if (mTestApk == null) {
                CLog.e("No test apk info provided.");
                return;
            }
            CLog.d("Collecting benchmark stats for layer: %s", mTestApk.getLayerName());

            String cmd = "dumpsys SurfaceFlinger --latency \"" + mTestApk.getLayerName()+ "\"";
            String[] raw = mDevice.executeShellCommand(cmd).split("\n");

            if (mFirstLoop) {
                if (raw.length == 1) {
                    // We didn't get any frame timestamp info.  Mostly likely because the app has
                    // not started yet.  Or the app layer name is wrong.
                    // TODO: figure out how to report it if the app layer name is wrong.
                    return;
                }
                mVSyncPeriod = Long.parseLong(raw[0]);
                mFirstLoop = false;
            }

            boolean overlap = false;
            for (int i = 1; i < raw.length; i++) {
                String[] parts = raw[i].split("\t");

                if (parts.length == 3) {
                    if (sample(Long.parseLong(parts[2]))) {
                        overlap = true;
                    }
                }
            }

            if (!overlap) {
                CLog.e("No overlap with previous poll, we missed some frames!"); // FIND SOMETHING BETTER
            }

        } catch (DeviceNotAvailableException | NullPointerException e) {
            CLog.e(e);
        }
    }

    private boolean sample(long timeStamp) {
        if (timeStamp == Long.MAX_VALUE) {
            return true;
        }
        else if (timeStamp < mLatestSeen) {
            return false;
        }
        else if (timeStamp == mLatestSeen) {
            return true;
        }
        else {
            mElapsedTimes.add(timeStamp);
            mLatestSeen = timeStamp;
            return false;
        }
    }


    private void onStart(DeviceMetricData runData) {}

    private void processTimestampsSlice(int runIndex, long startTimestamp, long endTimestamp, BufferedWriter outputFile, DeviceMetricData runData) throws IOException {
        double minFPS = Double.MAX_VALUE;
        double maxFPS = 0.0;
        long minFrameTime = Long.MAX_VALUE;
        long maxFrameTime = 0;
        long totalTimeNs = 0;

        outputFile.write("Started run " + runIndex + " at: " + startTimestamp + " ns \n");

        outputFile.write("Frame Time\t\tFrames Per Second\n");

        long prevTime = 0L;
        int numOfTimestamps = 0;

        for(long time : mElapsedTimes)
        {
            if (time < startTimestamp) {
                continue;
            }
            if (time > endTimestamp) {
                break;
            }

            if (prevTime == 0) {
                prevTime = time;
                continue;
            }

            long timeDiff = time - prevTime;
            prevTime = time;

            double currentFPS = 1.0e9/timeDiff;
            minFPS = (currentFPS < minFPS ? currentFPS : minFPS);
            maxFPS = (currentFPS > maxFPS ? currentFPS : maxFPS);

            minFrameTime = (timeDiff < minFrameTime ? timeDiff : minFrameTime);
            maxFrameTime = (timeDiff > maxFrameTime ? timeDiff : maxFrameTime);

            totalTimeNs += timeDiff;
            numOfTimestamps++;

            outputFile.write(timeDiff + " ns\t\t" + currentFPS + " fps\n");
        }

        // There's a fair amount of slop in the system wrt device timing vs host orchestration,
        // so it's possible that we'll receive an extra intent after we've stopped caring.
        if (numOfTimestamps == 0) {
            outputFile.write("No samples in period, assuming spurious intent.\n\n");
            return;
        }

        outputFile.write("\nSTATS\n");

        double avgFPS = numOfTimestamps * 1.0e9 / totalTimeNs;
        long avgFrameTime = totalTimeNs / numOfTimestamps;

        outputFile.write("max Frame Time: " + maxFrameTime + " ns\tmin FPS = " + minFPS + " fps\n");
        outputFile.write("min Frame Time: " + minFrameTime + " ns\tmax FPS = " + maxFPS + " fps\n");
        outputFile.write("avg Frame Time: " + avgFrameTime + " ns\tavg FPS = " + avgFPS + " fps\n");

        runData.addMetric("run_" + runIndex + ".min_fps", getFpsMetric(minFPS));
        runData.addMetric("run_" + runIndex + ".max_fps", getFpsMetric(maxFPS));
        runData.addMetric("run_" + runIndex + ".fps", getFpsMetric(avgFPS));

        runData.addMetric("run_" + runIndex + ".min_frametime", getFrameTimeMetric(minFrameTime));
        runData.addMetric("run_" + runIndex + ".max_frametime", getFrameTimeMetric(maxFrameTime));
        runData.addMetric("run_" + runIndex + ".frametime", getFrameTimeMetric(avgFrameTime));

        outputFile.write("\n");
    }

    private void onEnd(DeviceMetricData runData) {

        try {
            File tmpFile = File.createTempFile("GameQualification", ".txt");
            try (BufferedWriter outputFile = new BufferedWriter(new FileWriter(tmpFile))) {

                outputFile.write("VSync Period: " + mVSyncPeriod + "\n\n");

                if (mDeviceResultData.getEventsCount() == 0) {
                    CLog.w("No start intent given; assuming single run with no loading period to exclude.");
                }

                long startTime = 0L;
                int runIndex = 0;
                for (ResultDataProto.Event e : mDeviceResultData.getEventsList()) {
                    if (e.getType() != ResultDataProto.Event.Type.START_LOOP) {
                        continue;
                    }

                    long endTime = e.getTimestamp() * 1000000;  /* ms to ns */

                    if (startTime != 0) {
                        processTimestampsSlice(runIndex++, startTime, endTime, outputFile, runData);
                    }
                    startTime = endTime;
                }

                processTimestampsSlice(runIndex, startTime, mElapsedTimes.get(mElapsedTimes.size() - 1), outputFile, runData);

                try(InputStreamSource source = new FileInputStreamSource(tmpFile, true)) {
                    testLog("GameQualification-run" + mRunCount++, LogDataType.TEXT, source);
                }
                mRunCount++;
            }
            tmpFile.delete();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Metric.Builder getFpsMetric(double value) {
        return Metric.newBuilder()
            .setUnit("fps")
            .setDirection(Directionality.UP_BETTER)
            .setType(DataType.PROCESSED)
            .setMeasurements(Measurements.newBuilder().setSingleDouble(value));
    }

    private Metric.Builder getFrameTimeMetric(long value) {
    	return Metric.newBuilder()
    		.setUnit("ns")
    		.setDirection(Directionality.DOWN_BETTER)
    		.setType(DataType.PROCESSED)
    		.setMeasurements(Measurements.newBuilder().setSingleInt(value));
    }
}
