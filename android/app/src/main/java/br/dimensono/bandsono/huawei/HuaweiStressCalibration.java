/*  Copyright (C) 2025 Me7c7

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package br.dimensono.bandsono.huawei;

import android.os.CountDownTimer;

import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.HuaweiStressParser;
import br.dimensono.bandsono.huawei.packets.HrRriTest;
import br.dimensono.bandsono.huawei.stress.HuaweiStressHRVCalculation;
import br.dimensono.bandsono.huawei.stress.HuaweiStressScoreCalculation;
import br.dimensono.bandsono.huawei.requests.Request;
import br.dimensono.bandsono.huawei.requests.SendHROpenCloseRequest;

public class HuaweiStressCalibration {

    public interface HuaweiStressCalibrateCallback {
        /**
         * @param stressData stress baseline computed from the first minute, or null if it could not be computed
         * @param rri        RR intervals (ms) with signal quality > 0, in order
         * @param sqi        signal quality index of each RR interval
         */
        void onFinish(HuaweiStressParser.StressData stressData, int[] rri, int[] sqi);

        void onProgress(long j);

        void onError();
    }

    private static final Logger LOG = LoggerFactory.getLogger(HuaweiStressCalibration.class);

    private final HuaweiSupportProvider supportProvider;

    private RriDataReceiver rriDataReceiver;

    private HuaweiStressCalibrateCallback callback = null;

    private List<Integer> rriData = new ArrayList<>();
    private List<Integer> sqiData = new ArrayList<>();

    private long startTime = 0;
    private long endTime = 0;

    private CountDownTimer measureTimer = null;

    private static class RriDataReceiver extends Request {

        List<HrRriTest.RriData.Response.rriSqiData> data;

        public RriDataReceiver(HuaweiSupportProvider supportProvider) {
            super(supportProvider);
        }

        @Override
        public boolean handleResponse(HuaweiPacket response) {
            if ((response.serviceId == HrRriTest.id && response.commandId == HrRriTest.RriData.id)) {
                receivedPacket = response;
                return true;
            }
            return false;
        }

        @Override
        public boolean autoRemoveFromResponseHandler() {
            // This needs to be removed manually
            return false;
        }

        @Override
        protected void processResponse() throws ResponseParseException {
            if (this.receivedPacket instanceof HrRriTest.RriData.Response) {
                HrRriTest.RriData.Response response = (HrRriTest.RriData.Response) this.receivedPacket;
                data = response.containers;
            } else {
                throw new ResponseTypeMismatchException(this.receivedPacket, HrRriTest.RriData.class);
            }
        }
    }

    private final long durationMs;

    public HuaweiStressCalibration(HuaweiSupportProvider supportProvider) {
        this(supportProvider, 60000);
    }

    public HuaweiStressCalibration(HuaweiSupportProvider supportProvider, long durationMs) {
        this.supportProvider = supportProvider;
        this.durationMs = Math.max(60000, durationMs);
    }

    public boolean isRunning() {
        return this.measureTimer != null;
    }

    public void cancel() {
        if (this.measureTimer != null)
            stopMeasurements(true);
    }

    private void openOrClose(byte type) {
        try {
            SendHROpenCloseRequest req = new SendHROpenCloseRequest(this.supportProvider, type);
            req.doPerform();
        } catch (IOException e) {
            LOG.error("Failed to SendHROpenCloseRequest", e);
        }
    }

    public boolean startMeasurements(HuaweiStressCalibrateCallback callback) {
        if (this.measureTimer != null) {
            // measurement in progress
            return false;
        }

        this.callback = callback;

        if (rriDataReceiver == null) {
            // We can only init fileDataReceiver if the device is already connected
            rriDataReceiver = new RriDataReceiver(supportProvider);
            rriDataReceiver.setFinalizeReq(new Request.RequestCallback() {
                @Override
                public void call() {
                    for (int i = 0; i < rriDataReceiver.data.size(); i++) {
                        HrRriTest.RriData.Response.rriSqiData d = rriDataReceiver.data.get(i);
                        if (d.sqi != 0) {
                            rriData.add((int) d.rri);
                            sqiData.add((int) d.sqi);
                        }
                    }
                }

                @Override
                public void handleException(Request.ResponseParseException e) {
                    stopMeasurements(true);
                }
            });
        }
        this.supportProvider.addInProgressRequest(rriDataReceiver);

        rriData.clear();
        sqiData.clear();

        startTime = System.currentTimeMillis();
        endTime = 0;
        openOrClose((byte) 3);

        this.measureTimer = new CountDownTimer(durationMs, 1000) {
            @Override
            public void onTick(long j) {
                if (callback != null) {
                    callback.onProgress(j);
                }
            }

            @Override
            public void onFinish() {
                stopMeasurements(false);
            }
        };
        this.measureTimer.start();
        return true;
    }

    void stopMeasurements(boolean error) {
        openOrClose((byte) 4);
        endTime = System.currentTimeMillis();
        CountDownTimer timer = this.measureTimer;
        if (timer != null) {
            timer.cancel();
            this.measureTimer = null;
        }
        this.supportProvider.removeInProgressRequests(rriDataReceiver);
        if (error) {
            if (callback != null) {
                callback.onError();
            }
            return;
        }
        // health values (intervals, parameters, score) are never logged: only how many intervals came
        LOG.info("RR test finished, {} intervals", rriData.size());
        int[] rriAll = new int[rriData.size()];
        int[] sqiAll = new int[sqiData.size()];
        for (int i = 0; i < rriAll.length; i++) {
            rriAll[i] = rriData.get(i);
            sqiAll[i] = i < sqiAll.length ? sqiData.get(i) : 0;
        }

        // The band's stress algorithm works on one minute of data
        List<Integer> rriMinute = new ArrayList<>();
        List<Integer> sqiMinute = new ArrayList<>();
        long acc = 0;
        for (int i = 0; i < rriData.size() && acc < 60000; i++) {
            rriMinute.add(rriData.get(i));
            sqiMinute.add(sqiData.get(i));
            acc += rriData.get(i);
        }
        // Seconds of signal: wall clock, or the RR intervals themselves when the clock jumped
        int signalTime = (int) Math.min(60, Math.max((endTime - startTime) / 1000, Math.round(acc / 1000.0)));

        HuaweiStressParser.StressData stressData = null;
        try {
            HuaweiStressHRVCalculation hrvCalc = new HuaweiStressHRVCalculation();
            float[] hvrParams = hrvCalc.calculateStressHRVParameters(rriMinute, sqiMinute, signalTime);
            if (hvrParams != null && hvrParams.length == 10) {
                float scoreFactor = HuaweiStressScoreCalculation.calculateScoreFactor(hvrParams);
                byte stressScore = HuaweiStressScoreCalculation.calculateNormalizedFinalScore(scoreFactor);
                stressData = new HuaweiStressParser.StressData();
                for (float p : hvrParams) {
                    stressData.features.add(p);
                }
                // 10 and 11 elements are always 0
                stressData.features.add(0.0F);
                stressData.features.add(0.0F);
                stressData.startTime = startTime;
                stressData.endTime = endTime;
                stressData.score = stressScore;
                stressData.scoreFactor = scoreFactor;
                stressData.level = HuaweiStressScoreCalculation.calculateLevel(stressScore);
            }
        } catch (Exception e) {
            LOG.error("Stress calculation failed", e);
        }

        if (rriAll.length == 0 && stressData == null) {
            if (callback != null)
                callback.onError();
            return;
        }
        if (this.callback != null) {
            callback.onFinish(stressData, rriAll, sqiAll);
        }
    }


}
