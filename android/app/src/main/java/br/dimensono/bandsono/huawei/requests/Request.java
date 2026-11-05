/*  Copyright (C) 2024 Damien Gaignon, Martin.JM and Gadgetbridge contributors,
                  2026 BandSono adaptation

    This file is derived from Gadgetbridge and is free software: you can
    redistribute it and/or modify it under the terms of the GNU Affero General
    Public License as published by the Free Software Foundation, either
    version 3 of the License, or (at your option) any later version.

    It is distributed in the hope that it will be useful, but WITHOUT ANY
    WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
    FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for
    more details. <https://www.gnu.org/licenses/> */
package br.dimensono.bandsono.huawei.requests;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.List;

import br.dimensono.bandsono.huawei.BandDevice;
import br.dimensono.bandsono.huawei.HuaweiCrypto;
import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;
import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;

/**
 * Base of every request sent to the band.
 * Same contract as Gadgetbridge's Request (chaining, callbacks, timeouts), but the
 * packets are handed to {@link HuaweiSupportProvider#sendPackets} instead of a
 * Gadgetbridge TransactionBuilder.
 */
public class Request {
    private static final Logger LOG = LoggerFactory.getLogger(Request.class);

    public static class RequestCreationException extends Exception {
        public RequestCreationException(String message) {
            super(message);
        }

        public RequestCreationException(HuaweiPacket.CryptoException e) {
            super(e);
        }

        public RequestCreationException(String message, Exception e) {
            super(message, e);
        }
    }

    public static class ResponseParseException extends Exception {
        public ResponseParseException(String message) {
            super(message);
        }

        public ResponseParseException(Exception e) {
            super(e);
        }

        public ResponseParseException(String message, Exception e) {
            super(message, e);
        }
    }

    public static class ResponseTypeMismatchException extends ResponseParseException {
        public ResponseTypeMismatchException(HuaweiPacket a, Class<?> b) {
            super("Response type mismatch, packet is of type " + a.getClass() + " but expected " + b);
        }

        public ResponseTypeMismatchException(HuaweiPacket a, Class<?> b, Class<?> c) {
            super("Response type mismatch, packet is of type " + a.getClass() + " but expected " + b + " or " + c);
        }
    }

    protected byte serviceId;
    protected byte commandId;
    protected HuaweiPacket sendingPacket = null;
    protected HuaweiPacket receivedPacket = null;
    protected HuaweiSupportProvider supportProvider;
    protected HuaweiPacket.ParamsProvider paramsProvider;

    // Be able to autostart a request after this one
    protected Request nextRequest = null;
    protected boolean isSelfQueue = true;
    // Callback function to start after the request
    protected RequestCallback finalizeReq = null;
    // Stop chaining requests and clean support.inProgressRequests from these requests
    protected boolean stopChain = false;

    protected HuaweiCrypto huaweiCrypto = null;

    protected boolean addToResponse = true;

    private final Handler handler;
    private final Runnable timeoutRunner;
    private Integer timeout = null;

    public static class RequestCallback {
        protected HuaweiSupportProvider support = null;

        public RequestCallback() {
        }

        public RequestCallback(HuaweiSupportProvider supportProvider) {
            support = supportProvider;
        }

        public void call() {
        }

        public void call(Request request) {
            call(); // To keep everything working as it was as well
        }

        public void handleException(ResponseParseException e) {
            LOG.error("Callback request exception", e);
        }

        public void handleException(Request request, ResponseParseException e) {
            handleException(e); // To keep everything working as it was as well
        }

        public void timeout(Request request) {
            request.handleNext();
        }
    }

    public Request(HuaweiSupportProvider supportProvider) {
        this.supportProvider = supportProvider;
        this.paramsProvider = supportProvider.getParamsProvider();
        this.handler = new Handler(Looper.getMainLooper());
        this.timeoutRunner = () -> {
            LOG.debug("Timeout on Service {} command {}", Integer.toHexString(this.serviceId & 0xff), Integer.toHexString(this.commandId & 0xff));
            supportProvider.removeInProgressRequests(this);
            if (finalizeReq != null)
                finalizeReq.timeout(this);
            else
                this.handleNext();
        };
    }

    protected boolean requestSupported() {
        return true;
    }

    public void doPerform() throws IOException {
        if (!supportProvider.isConnected()) {
            throw new IOException("Band is not connected");
        }
        if (!requestSupported()) {
            LOG.debug("Request {} not supported by the band, skipping", getName());
            this.handleNext();
            return;
        }

        if (this.addToResponse) {
            supportProvider.addInProgressRequest(this);
        }

        try {
            List<byte[]> packets = createRequest();
            if (packets != null)
                supportProvider.sendPackets(packets, getName());
        } catch (RequestCreationException e) {
            supportProvider.removeInProgressRequests(this);
            // We cannot throw the RequestCreationException, so we throw an IOException
            throw new IOException("Request could not be created", e);
        }

        if (this.timeout != null)
            handler.postDelayed(this.timeoutRunner, this.timeout);
    }

    protected List<byte[]> createRequest() throws RequestCreationException {
        if (sendingPacket != null) {
            try {
                return sendingPacket.serialize();
            } catch (HuaweiPacket.CryptoException e) {
                throw new RequestCreationException(e);
            }
        }
        return null;
    }

    protected void processResponse() throws ResponseParseException {
    }

    public void handleResponse() {
        // Stop timeout timer
        this.handler.removeCallbacks(this.timeoutRunner);

        try {
            this.receivedPacket.parseTlv();
        } catch (HuaweiPacket.ParseException e) {
            LOG.error("Parse TLV exception in " + getName(), e);
            if (finalizeReq != null) {
                finalizeReq.handleException(this, new ResponseParseException("Parse TLV exception", e));
            } else {
                // Nobody to report to: keep the chain going instead of stalling it
                handleNext();
            }
            return;
        }
        try {
            processResponse();
        } catch (ResponseParseException e) {
            if (finalizeReq != null) {
                finalizeReq.handleException(this, e);
            } else {
                LOG.error("Response exception in " + getName(), e);
                handleNext();
            }
            return;
        }
        handleNext();
    }

    public void handleNext() {
        this.handler.removeCallbacks(this.timeoutRunner);
        if (nextRequest != null && !stopChain) {
            Request next = nextRequest;
            nextRequest = null;
            try {
                next.doPerform();
            } catch (IOException e) {
                LOG.error("Next request failed", e);
                if (finalizeReq != null)
                    finalizeReq.handleException(this, new ResponseParseException("Next request failed", e));
            }
            return;
        }
        if (finalizeReq != null) {
            finalizeReq.call(this);
        }
        nextRequest = null;
    }

    public void setSelfQueue() {
        isSelfQueue = true;
    }

    public Request nextRequest(Request req) {
        if (req != null) {
            nextRequest = req;
            nextRequest.setSelfQueue();
        }
        return this;
    }

    public void stopChain(Request req) {
        req.stopChain();
        Request next = req.nextRequest;
        if (next != null) {
            next.stopChain(next);
            supportProvider.removeInProgressRequests(next);
        }
    }

    public void stopChain() {
        stopChain = true;
    }

    /**
     * Handler for responses from the device
     * @param response The response packet
     * @return True if this request handles this response, false otherwise
     */
    public boolean handleResponse(HuaweiPacket response) {
        if (response.serviceId == serviceId && response.commandId == commandId) {
            receivedPacket = response;
            return true;
        }
        return false;
    }

    protected Context getContext() {
        return supportProvider.getContext();
    }

    protected BandDevice getDevice() {
        return supportProvider.getDevice();
    }

    public String getName() {
        Class<?> thisClass = getClass();
        while (thisClass.isAnonymousClass()) thisClass = thisClass.getSuperclass();
        return thisClass.getSimpleName();
    }

    public void setFinalizeReq(RequestCallback finalizeReq) {
        this.finalizeReq = finalizeReq;
    }

    /** Only used by Gadgetbridge's dual RFCOMM channel routing; BLE bands ignore it. */
    protected String getDualChannelDestPackage() {
        return null;
    }

    public boolean autoRemoveFromResponseHandler() {
        return true;
    }

    public void setupTimeoutUntilNext(int timeout) {
        this.timeout = timeout;
    }

    public void cancelTimeout() {
        this.handler.removeCallbacks(this.timeoutRunner);
    }
}
