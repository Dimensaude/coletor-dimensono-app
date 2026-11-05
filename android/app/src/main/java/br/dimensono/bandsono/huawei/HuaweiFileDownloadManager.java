/*  Copyright (C) 2024 Martin.JM

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

import android.os.Handler;
import android.os.Looper;


import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.packets.FileDownloadService0A;
import br.dimensono.bandsono.huawei.packets.FileDownloadService2C;
import br.dimensono.bandsono.huawei.requests.GetFileBlockRequest;
import br.dimensono.bandsono.huawei.requests.GetFileDownloadCompleteRequest;
import br.dimensono.bandsono.huawei.requests.GetFileDownloadInitRequest;
import br.dimensono.bandsono.huawei.requests.GetFileHashRequest;
import br.dimensono.bandsono.huawei.requests.GetFileIncomingAck;
import br.dimensono.bandsono.huawei.requests.GetFileInfoRequest;
import br.dimensono.bandsono.huawei.requests.GetFileParametersRequest;
import br.dimensono.bandsono.huawei.requests.Request;
import br.dimensono.bandsono.huawei.util.GB;

public class HuaweiFileDownloadManager {
    private static final Logger LOG = LoggerFactory.getLogger(HuaweiFileDownloadManager.class);

    public static class HuaweiFileDownloadException extends Exception {
        
        public final FileRequest fileRequest;

        HuaweiFileDownloadException(FileRequest fileRequest, String message) {
            super(message);
            this.fileRequest = fileRequest;
        }

        HuaweiFileDownloadException(FileRequest fileRequest, String message, Exception e) {
            super(message, e);
            this.fileRequest = fileRequest;
        }
    }

    public static class HuaweiFileDownloadSendException extends HuaweiFileDownloadException {
        HuaweiFileDownloadSendException(FileRequest fileRequest, Request request, Exception e) {
            super(fileRequest, "Error sending request " + request.getName(), e);
        }
    }

    public static class HuaweiFileDownloadRequestException extends HuaweiFileDownloadException {
        HuaweiFileDownloadRequestException(FileRequest fileRequest, Class<?> requestClass, Exception e) {
            super(fileRequest, "Error in request of class " + requestClass, e);
        }
    }

    /** The band answered the request with an error code (e.g. 144001 = there is no such file). */
    public static class HuaweiFileDownloadDeviceErrorException extends HuaweiFileDownloadException {
        public final int code;

        HuaweiFileDownloadDeviceErrorException(FileRequest fileRequest, int code) {
            super(fileRequest, "The band answered with error " + code);
            this.code = code;
        }
    }

    public static class HuaweiFileDownloadTimeoutException extends HuaweiFileDownloadException {
        HuaweiFileDownloadTimeoutException(FileRequest fileRequest) {
            super(fileRequest, "Timeout was hit!");
        }
    }

    public static class HuaweiFileDownloadVerifyException extends HuaweiFileDownloadException {
        HuaweiFileDownloadVerifyException(FileRequest fileRequest) {
            super(fileRequest, "Verify failed");
        }
    }

    public static class HuaweiFileDownloadFileMismatchException extends HuaweiFileDownloadException {
        HuaweiFileDownloadFileMismatchException(FileRequest fileRequest, String filename) {
            super(fileRequest, "Data for wrong file received. Expected name " + fileRequest.filename + ", got name " + filename);
        }

        HuaweiFileDownloadFileMismatchException(FileRequest fileRequest, FileType fileType) {
            super(fileRequest, "Data for wrong file received. Expected type " + fileRequest.fileType + ", got type " + fileType);
        }

        HuaweiFileDownloadFileMismatchException(FileRequest fileRequest, String[] filenames) {
            super(fileRequest, "File " + fileRequest.filename + " cannot be downloaded using this method. Only the following files are supported: " + Arrays.toString(filenames));
        }

        HuaweiFileDownloadFileMismatchException(FileRequest fileRequest, int number, boolean newSync) {
            super(
                    fileRequest,
                    "Data for wrong file received. Expected " +
                            (newSync ?
                                    "id " + fileRequest.fileId + ", got id " + number :
                                    "packet number " + (fileRequest.lastPacketNumber + 1) + ", got " + number)
            );
        }
    }

    public enum FileType {
        DEBUG,
        SLEEP_STATE,
        SLEEP_DATA,
        RRI,
        GPS,
        PDR,
        SEQUENCE_DATA,
        ECG_ANALYSIS_DATA,
        UNKNOWN // Never for input!
    }

    public static class FileDownloadCallback {
        public void downloadComplete(FileRequest fileRequest, File localRawFile) {
        }

        public void downloadException(HuaweiFileDownloadException e) {
            if (e.fileRequest != null)
                LOG.error("Error downloading file: {}{}", e.fileRequest.getFilename(), e.fileRequest.isNewSync() ? " (newsync)" : "", e);
            else
                LOG.error("Error in file download", e);
        }
    }

    public static class FileRequest {
        // Inputs

        private final String filename;
        private final FileType fileType;
        private final boolean newSync;

        private final FileDownloadCallback fileDownloadCallback;

        // Sleep type only - for 2C GPS they are set to zero
        private int startTime = 0;
        private int endTime = 0;

        private int dictId = 0;

        // GPS type only
        private short workoutId;
        private Long databaseId;


        private boolean initFormDevice = false;

        private FileRequest(String filename, FileType fileType, boolean newSync, int startTime, int endTime, FileDownloadCallback fileDownloadCallback) {
            this.filename = filename;
            this.fileType = fileType;
            this.newSync = newSync;
            this.fileDownloadCallback = fileDownloadCallback;
            this.startTime = startTime;
            this.endTime = endTime;
        }

        public static FileRequest IncomingFileRequest(String filename, FileDownloadCallback fileDownloadCallback) {
            return new FileRequest(filename, FileType.UNKNOWN, true, fileDownloadCallback);
        }

        public static FileRequest sleepStateFileRequest(boolean supportsTruSleepNewSync, int startTime, int endTime, FileDownloadCallback fileDownloadCallback) {
            return new FileRequest("sleep_state.bin", FileType.SLEEP_STATE, supportsTruSleepNewSync, startTime, endTime, fileDownloadCallback);
        }

        public static FileRequest sleepDataFileRequest(boolean supportsTruSleepNewSync, int startTime, int endTime, FileDownloadCallback fileDownloadCallback) {
            return new FileRequest("sleep_data.bin", FileType.SLEEP_DATA, supportsTruSleepNewSync, startTime, endTime, fileDownloadCallback);
        }

        public static FileRequest rriFileRequest(boolean supportsRriNewSync, int startTime, int endTime, FileDownloadCallback fileDownloadCallback) {
            return new FileRequest("rrisqi_data.bin", FileType.RRI, supportsRriNewSync, startTime, endTime, fileDownloadCallback);
        }

        public static FileRequest ecgAnalysisFileRequest(int startTime, int endTime, FileDownloadCallback fileDownloadCallback) {
            return new FileRequest("ecg_analysis_data.bin", FileType.ECG_ANALYSIS_DATA, true, startTime, endTime, fileDownloadCallback);
        }


        private FileRequest(String filename, FileType fileType, int startTime, int endTime, int dictId, FileDownloadCallback fileDownloadCallback) {
            this.filename = filename;
            this.fileType = fileType;
            this.newSync = true;
            this.fileDownloadCallback = fileDownloadCallback;
            this.startTime = startTime;
            this.endTime = endTime;
            this.dictId = dictId;
        }

        public static FileRequest sequenceDataFileRequest(int startTime, int endTime, int dictId, FileDownloadCallback fileDownloadCallback) {
            return new FileRequest("sequence_data", FileType.SEQUENCE_DATA, startTime, endTime, dictId, fileDownloadCallback);
        }

        private FileRequest(String filename, FileType fileType, boolean newSync, FileDownloadCallback fileDownloadCallback) {
            this.filename = filename;
            this.fileType = fileType;
            this.newSync = newSync;
            this.fileDownloadCallback = fileDownloadCallback;
        }

        public static FileRequest debugFileRequest(String filename, FileDownloadCallback fileDownloadCallback) {
            return new FileRequest(filename, FileType.DEBUG, false, fileDownloadCallback);
        }

        private FileRequest(String filename, FileType fileType, boolean newSync, short workoutId, Long databaseId, FileDownloadCallback fileDownloadCallback) {
            this.filename = filename;
            this.fileType = fileType;
            this.newSync = newSync;
            this.fileDownloadCallback = fileDownloadCallback;
            this.workoutId = workoutId;
            this.databaseId = databaseId;
        }

        public static FileRequest workoutGpsFileRequest(boolean newSync, short workoutId, Long databaseId, FileDownloadCallback fileDownloadCallback) {
            if (newSync)
                return new FileRequest(String.format(Locale.getDefault(), "%d_gps.bin", workoutId), FileType.GPS, true, workoutId, databaseId, fileDownloadCallback);
            else
                return new FileRequest(null, FileType.GPS, false, workoutId, databaseId, fileDownloadCallback);
        }

        public static FileRequest workoutPdrFileRequest(short workoutId, Long databaseId, FileDownloadCallback fileDownloadCallback) {
            return new FileRequest(String.format(Locale.getDefault(), "%d_pdr.bin", workoutId), FileType.PDR, true, workoutId, databaseId, fileDownloadCallback);
        }

        // Retrieved

        private int fileSize;
        private int maxBlockSize;
        private int timeout; // TODO: unit?
        private ByteBuffer buffer;

        private int startOfBlockOffset;
        private int currentBlockSize;

        // Old sync only
        private String[] filenames;
        private byte lastPacketNumber;

        // New sync only
        private byte fileId;
        private boolean noEncrypt;
        private boolean needVerify = false;
        private byte[] fileHash = null;

        // Incoming P2P request
        private byte inFileType;
        private String srcPackage = null;
        private String dstPackage = null;
        private String srcFingerprint = null;
        private String dstFingerprint = null;

        public byte getFileId() {
            return fileId;
        }

        public String getFilename() {
            return filename;
        }

        public FileType getFileType() {
            return fileType;
        }

        public byte[] getData() {
            if (buffer == null)
                return new byte[]{};
            return buffer.array();
        }

        public int getCurrentOffset() {
            return buffer.position();
        }

        public boolean isNewSync() {
            return newSync;
        }

        public int getStartTime() {
            return startTime;
        }

        public int getEndTime() {
            return endTime;
        }

        public short getWorkoutId() {
            return workoutId;
        }

        public Long getDatabaseId() {
            return databaseId;
        }

        public int getCurrentBlockSize() {
            return currentBlockSize;
        }

        public boolean isNoEncrypt() {
            return noEncrypt;
        }

        public boolean isInitFormDevice() {
            return initFormDevice;
        }

        public void setInitFormDevice(boolean initFormDevice) {
            this.initFormDevice = initFormDevice;
        }

        public String getSrcPackage() {
            return srcPackage;
        }

        public void setSrcPackage(String srcPackage) {
            this.srcPackage = srcPackage;
        }

        public String getDstPackage() {
            return dstPackage;
        }

        public void setDstPackage(String dstPackage) {
            this.dstPackage = dstPackage;
        }

        public String getSrcFingerprint() {
            return srcFingerprint;
        }

        public void setSrcFingerprint(String srcFingerprint) {
            this.srcFingerprint = srcFingerprint;
        }

        public String getDstFingerprint() {
            return dstFingerprint;
        }

        public void setDstFingerprint(String dstFingerprint) {
            this.dstFingerprint = dstFingerprint;
        }

        public void setFileId(byte fileId) {
            this.fileId = fileId;
        }

        public int getFileSize() {
            return fileSize;
        }

        public void setFileSize(int fileSize) {
            this.fileSize = fileSize;
        }

        public byte getInFileType() {
            return inFileType;
        }

        public void setInFileType(byte inFileType) {
            this.inFileType = inFileType;
        }

        public boolean isNeedVerify() {
            return needVerify;
        }

        public void setNeedVerify(boolean needVerify) {
            this.needVerify = needVerify;
        }

        public int getDictId() { return dictId;}

        public void setDictId(int dictId) { this.dictId = dictId;}
    }

    /**
     * Actually receives the file data
     */
    private static class FileDataReceiver extends Request {

        public boolean newSync;
        public byte[] data;

        // Packet number for old sync
        // fileId for new sync
        public byte number;

        public FileDataReceiver(HuaweiSupportProvider supportProvider) {
            super(supportProvider);
        }

        @Override
        public boolean handleResponse(HuaweiPacket response) {
            if (
                    (response.serviceId == FileDownloadService0A.id &&
                            response.commandId == FileDownloadService0A.BlockResponse.id) ||
                            (response.serviceId == FileDownloadService2C.id &&
                                    response.commandId == FileDownloadService2C.BlockResponse.id)
            ) {
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
            if (this.receivedPacket instanceof FileDownloadService0A.BlockResponse) {
                FileDownloadService0A.BlockResponse response = (FileDownloadService0A.BlockResponse) this.receivedPacket;
                this.newSync = false;
                this.number = response.number;
                this.data = response.data;

                if (response.e instanceof HuaweiPacket.CryptoException) {
                    LOG.warn("Data could be decoded as TLV, but not decrypted.", response.e);
                }
            } else if (this.receivedPacket instanceof FileDownloadService2C.BlockResponse) {
                this.newSync = true;
                this.number = ((FileDownloadService2C.BlockResponse) this.receivedPacket).fileId;
                this.data = ((FileDownloadService2C.BlockResponse) this.receivedPacket).data;
            } else {
                throw new ResponseTypeMismatchException(
                        this.receivedPacket,
                        FileDownloadService0A.BlockResponse.class,
                        FileDownloadService2C.BlockResponse.class
                );
            }
        }
    }

    private final HuaweiSupportProvider supportProvider;
    private final Handler handler;
    private final Runnable timeout;

    // Cannot be final as we need the device to be connected before creating this
    private FileDataReceiver fileDataReceiver;

    // For old sync we cannot download multiple files at the same time, for new sync we don't want
    // to, so we limit that. We also do not allow old and new sync at the same time.
    // Note that old and new sync are already split by the serviceID and commandID that are used for
    // all the requests.
    // Note that the timeout is not ready for concurrent downloads
    private boolean isBusy;

    private final ArrayList<FileRequest> fileRequests;
    private FileRequest currentFileRequest;

    // If the GB interface needs to be updated at the end of the queue of downloads
    private boolean needSync = false;

    public HuaweiFileDownloadManager(HuaweiSupportProvider supportProvider) {
        this.supportProvider = supportProvider;
        handler = new Handler(Looper.getMainLooper());
        isBusy = false;
        fileRequests = new ArrayList<>();
        timeout = () -> {
            if (this.currentFileRequest == null) return;
            this.currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadTimeoutException(currentFileRequest));
            reset();
        };
    }

    public void addToQueue(FileRequest fileRequest, boolean needSync) {
        synchronized (supportProvider) {
            fileRequests.add(fileRequest);
            if (needSync)
                this.needSync = true;
        }
        if (fileRequest.isInitFormDevice()) {
            GetFileIncomingAck getFileIncomingAck = new GetFileIncomingAck(supportProvider, fileRequest, (byte) 0);
            try {
                getFileIncomingAck.doPerform();
            } catch (IOException e) {
                LOG.error("Error execute", e);
            }
        }
        startDownload();
    }

    private boolean arrayContains(String[] haystack, String needle) {
        return Arrays.stream(haystack).anyMatch(s -> s.equals(needle));
    }

    private void initFileDataReceiver() {
        if (fileDataReceiver == null) {
            // We can only init fileDataReceiver if the device is already connected
            fileDataReceiver = new FileDataReceiver(supportProvider);
            fileDataReceiver.setFinalizeReq(new Request.RequestCallback() {
                @Override
                public void call() {
                    // Reset timeout
                    handler.removeCallbacks(HuaweiFileDownloadManager.this.timeout);
                    handler.postDelayed(HuaweiFileDownloadManager.this.timeout, currentFileRequest.timeout * 1000L);

                    // Handle data
                    handleFileData(fileDataReceiver.newSync, fileDataReceiver.number, fileDataReceiver.data);
                }

                @Override
                public void handleException(Request.ResponseParseException e) {
                    currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadRequestException(null, this.getClass(), e));
                }
            });
        }
    }

    public void startDownload() {
        initFileDataReceiver(); // Make sure the fileDataReceiver is ready

        synchronized (this.supportProvider) {
            if (this.isBusy) {
                LOG.debug("A new download is started while a previous is in progress.");
                return; // Already downloading, this file will come eventually
            }
            if (this.fileRequests.isEmpty()) {
                // No more files to download
                supportProvider.downloadQueueEmpty(this.needSync);
                // Don't need sync after this anymore
                this.needSync = false;
                return;
            }
            this.isBusy = true;
        }

        this.currentFileRequest = this.fileRequests.remove(0);

        if (this.currentFileRequest.isInitFormDevice()) {
            getFileHash();
            return;
        }

        sendFileDownloadInit();
    }

    /**
     * Sends the FileDownloadInit for {@link #currentFileRequest}. With dual channel active the
     * request gets a short timeout: the Watch 4 firmware silently ignores 0x2C on the main socket
     * while dual channel is up (but answers immediately on aux), so on timeout the init is retried
     * once with 0x2C rerouted to the aux socket for the rest of the connection
     * (dual channel handling removed: BLE bands only).
     */
    private void sendFileDownloadInit() {
        GetFileDownloadInitRequest getFileDownloadInitRequest = new GetFileDownloadInitRequest(supportProvider, currentFileRequest);
        getFileDownloadInitRequest.setupTimeoutUntilNext(15000);
        getFileDownloadInitRequest.setFinalizeReq(new Request.RequestCallback() {
            @Override
            public void call(Request request) {
                // For multi-download, match to file instead of assuming current
                GetFileDownloadInitRequest r = (GetFileDownloadInitRequest) request;
                if (r.newSync) {
                    if (r.errorCode != 0 && r.errorCode != 0x186A0) { // 0x186A0 is the plain "success" code
                        currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadDeviceErrorException(currentFileRequest, r.errorCode));
                        reset();
                        return;
                    }
                    if (!currentFileRequest.filename.equals(r.filename)) {
                        currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadFileMismatchException(
                                currentFileRequest,
                                r.filename
                        ));
                        reset();
                        return;
                    }
                    if (currentFileRequest.fileType != r.fileType) {
                        currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadFileMismatchException(
                                currentFileRequest,
                                r.fileType
                        ));
                        reset();
                        return;
                    }
                    currentFileRequest.fileId = r.fileId;
                    currentFileRequest.fileSize = r.fileSize;
                    if (r.fileSize == 0) {
                        // Nothing to download, go to end
                        fileComplete();
                        return;
                    }
                    getFileInfo();
                } else {
                    if (currentFileRequest.fileType == FileType.GPS) {
                        // Old sync lists the workout's files; only the gps file itself is downloaded. Whatever happens, the
                        // request gets an answer (no list / no gps file = an exception to the callback) and the queue is not touched.
                        FileRequest found = null;
                        for (String filename : r.filenames) {
                            if (filename.contains("_gps.bin")) {
                                found = new FileRequest(filename, FileType.GPS, currentFileRequest.newSync,
                                        currentFileRequest.workoutId, currentFileRequest.databaseId, currentFileRequest.fileDownloadCallback);
                                break;
                            }
                        }
                        if (found == null) {
                            currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadException(
                                    currentFileRequest, "The band listed no gps file for workout " + currentFileRequest.workoutId));
                            reset();
                            return;
                        }
                        currentFileRequest = found; // replaces the request with the file to download
                    } else {
                        if (!arrayContains(r.filenames, currentFileRequest.filename)) {
                            currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadFileMismatchException(
                                    currentFileRequest,
                                    r.filenames
                            ));
                            reset();
                            return;
                        }
                    }
                    currentFileRequest.filenames = r.filenames;
                    getDownloadParameters();
                }
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadRequestException(currentFileRequest, this.getClass(), e));
                reset();
            }

            @Override
            public void timeout(Request request) {
                // Watch never answered the init — drop the stale handler so a late or retried
                // response cannot be delivered to this dead request.
                supportProvider.removeInProgressRequests(request);
                    currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadTimeoutException(currentFileRequest));
                    reset();
            }
        });
        try {
            getFileDownloadInitRequest.doPerform();
        } catch (IOException e) {
            currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadSendException(currentFileRequest, getFileDownloadInitRequest, e));
            reset();
        }
    }

    private void getDownloadParameters() {
        // Old sync only, can never be multiple at the same time
        // Assuming currentRequest is the correct one the entire time
        // Which may no longer be the case when we implement multi-download for new sync
        byte fileType;

        if (currentFileRequest.fileType == HuaweiFileDownloadManager.FileType.DEBUG)
            fileType = 0x00;
        else if (currentFileRequest.fileType == FileType.SLEEP_STATE || currentFileRequest.fileType == FileType.SLEEP_DATA)
            fileType = 0x01;
        else if (currentFileRequest.fileType == HuaweiFileDownloadManager.FileType.GPS)
            fileType = 0x02;
        else if (currentFileRequest.fileType == HuaweiFileDownloadManager.FileType.RRI)
            fileType = 0x04;
        else {
            currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadException(currentFileRequest, "Unknown download type"));
            reset();
            return;
        }

        GetFileParametersRequest getFileParametersRequest = new GetFileParametersRequest(supportProvider, fileType);
        getFileParametersRequest.setFinalizeReq(new Request.RequestCallback() {
            @Override
            public void call() {
                currentFileRequest.maxBlockSize = getFileParametersRequest.getMaxBlockSize();
                currentFileRequest.timeout = getFileParametersRequest.getTimeout();
                getFileInfo();
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadRequestException(null, this.getClass(), e));
                reset();
            }
        });
        try {
            getFileParametersRequest.doPerform();
        } catch (IOException e) {
            currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadSendException(currentFileRequest, getFileParametersRequest, e));
            reset();
        }
    }

    private void getFileHash() {
        if (!currentFileRequest.isNeedVerify() || !currentFileRequest.isNewSync()) {
            getFileInfo();
            return;
        }
        GetFileHashRequest getFileHashRequest = new GetFileHashRequest(supportProvider, currentFileRequest);
        getFileHashRequest.setFinalizeReq(new Request.RequestCallback() {
            @Override
            public void call(Request request) {
                GetFileHashRequest r = (GetFileHashRequest) request;
                if (currentFileRequest.fileId != r.fileId) {
                    currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadFileMismatchException(currentFileRequest, r.fileId, true));
                    reset();
                    return;
                }
                currentFileRequest.fileHash = r.fileHash;
                getFileInfo();
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadRequestException(null, this.getClass(), e));
                reset();
            }
        });
        try {
            getFileHashRequest.doPerform();
        } catch (IOException e) {
            currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadSendException(currentFileRequest, getFileHashRequest, e));
            reset();
        }
    }

    private void getFileInfo() {
        GetFileInfoRequest getFileInfoRequest = new GetFileInfoRequest(supportProvider, currentFileRequest);
        getFileInfoRequest.setFinalizeReq(new Request.RequestCallback() {
            @Override
            public void call(Request request) {
                GetFileInfoRequest r = (GetFileInfoRequest) request;
                if (r.newSync) {
                    if (currentFileRequest.fileId != r.fileId) {
                        currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadFileMismatchException(currentFileRequest, r.fileId, true));
                        reset();
                        return;
                    }
                    currentFileRequest.timeout = r.timeout;
                    currentFileRequest.maxBlockSize = r.maxBlockSize;
                    currentFileRequest.noEncrypt = r.noEncrypt;
                } else {
                    // currentFileRequest MUST BE correct here
                    currentFileRequest.fileSize = r.fileLength;

                    if (currentFileRequest.fileSize == 0) {
                        // Nothing to download, go to complete
                        fileComplete();
                        return;
                    }
                }
                downloadNextFileBlock();
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadRequestException(null, this.getClass(), e));
                reset();
            }
        });
        try {
            getFileInfoRequest.doPerform();
        } catch (IOException e) {
            currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadSendException(currentFileRequest, getFileInfoRequest, e));
            reset();
        }
    }

    private void downloadNextFileBlock() {
        if (currentFileRequest.buffer == null) { // New file
            if (currentFileRequest.fileSize < 0) {
                // The watch reported an invalid/negative file size (seen as 0xFFFFFFFF, together with
                // an error status TLV, for a sequence_data file it has no data for). Fail this file
                // gracefully instead of ByteBuffer.allocate(-1), which throws and kills the socket
                // read thread, stalling the whole sync.
                currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadException(
                        currentFileRequest, "Invalid file size reported by device: " + currentFileRequest.fileSize));
                reset();
                return;
            }
            currentFileRequest.buffer = ByteBuffer.allocate(currentFileRequest.fileSize);
        }
        currentFileRequest.lastPacketNumber = -1; // Counts per block
        currentFileRequest.startOfBlockOffset = currentFileRequest.buffer.position();
        currentFileRequest.currentBlockSize = Math.min(
                currentFileRequest.fileSize - currentFileRequest.buffer.position(), // Remaining file size
                currentFileRequest.maxBlockSize // Max we can ask for
        );

        // Start listening for file data
        this.supportProvider.addInProgressRequest(fileDataReceiver);

        handler.removeCallbacks(this.timeout);
        handler.postDelayed(HuaweiFileDownloadManager.this.timeout, currentFileRequest.timeout * 1000L);

        GetFileBlockRequest getFileBlockRequest = new GetFileBlockRequest(supportProvider, currentFileRequest);
        getFileBlockRequest.setFinalizeReq(new Request.RequestCallback() {
            @Override
            public void call() {
                // Reset timeout
                handler.removeCallbacks(HuaweiFileDownloadManager.this.timeout);
                handler.postDelayed(HuaweiFileDownloadManager.this.timeout, currentFileRequest.timeout * 1000L);
            }
        });
        try {
            getFileBlockRequest.doPerform();
        } catch (IOException e) {
            currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadSendException(currentFileRequest, getFileBlockRequest, e));
            reset();
        }
    }

    private void handleFileData(boolean newSync, byte number, byte[] data) {
        if (newSync && currentFileRequest.fileId != number) {
            currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadFileMismatchException(currentFileRequest, number, true));
            reset();
            return;
        }
        if (!newSync) {
            if (currentFileRequest.lastPacketNumber != number - 1) {
                currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadFileMismatchException(currentFileRequest, number, false));
                reset();
                return;
            }
            currentFileRequest.lastPacketNumber = number;
        }

        currentFileRequest.buffer.put(data);

        if (currentFileRequest.buffer.position() >= currentFileRequest.fileSize) {
            // File complete
            LOG.info("Download complete for file {}", currentFileRequest.filename);
            if (currentFileRequest.buffer.position() != currentFileRequest.fileSize) {
                LOG.error("Downloaded file is larger than expected: {}", currentFileRequest.filename);
            }
            fileComplete();
        } else if (currentFileRequest.buffer.position() - currentFileRequest.startOfBlockOffset >= currentFileRequest.maxBlockSize) {
            // Block complete, need to request a new file block
            downloadNextFileBlock();
        } // Else we're expecting more data to arrive automatically
    }

    
    private File saveRawFileData(FileRequest fileRequest) {
        return null;
    }

    private void fileComplete() {
        // Stop timeout from hitting
        this.handler.removeCallbacks(this.timeout);


        byte status = 1;
        if (currentFileRequest.isNeedVerify() && currentFileRequest.isNewSync()) {
            status = 2;
            try {
                if (currentFileRequest.fileHash != null) {
                    MessageDigest m = MessageDigest.getInstance("SHA256");
                    m.update(currentFileRequest.getData(), 0, currentFileRequest.fileSize);
                    byte[] sha256 = m.digest();
                    LOG.info("SHA256: {} {}", GB.hexdump(sha256), GB.hexdump(currentFileRequest.fileHash));
                    if (Arrays.equals(sha256, currentFileRequest.fileHash)) {
                        status = 1;
                    }
                }
            } catch (Exception e) {
                LOG.error("Error verify SHA256", e);
            }
        }

        // File complete request
        GetFileDownloadCompleteRequest getFileDownloadCompleteRequest = new GetFileDownloadCompleteRequest(supportProvider, currentFileRequest, status);
        try {
            getFileDownloadCompleteRequest.doPerform();
        } catch (IOException e) {
            currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadSendException(currentFileRequest, getFileDownloadCompleteRequest, e));
            reset();
            return;
        }

        if (status != 1) {
            currentFileRequest.fileDownloadCallback.downloadException(new HuaweiFileDownloadVerifyException(currentFileRequest));
            reset();
            return;
        }

        // Handle file data

        final File rawFileData = saveRawFileData(currentFileRequest);

        try {
            currentFileRequest.fileDownloadCallback.downloadComplete(currentFileRequest, rawFileData);
        } catch (Exception e) {
            LOG.error("Download complete callback exception.", e);
        }

        if (!this.currentFileRequest.newSync && !this.fileRequests.isEmpty() && !this.fileRequests.get(0).newSync) {
            // Old sync can potentially take a shortcut
            if (arrayContains(this.currentFileRequest.filenames, this.fileRequests.get(0).filename)) {
                // Shortcut to next download
                // - No init
                // - No getting parameters
                // Just copy over the info and go directly to GetFileInfo
                FileRequest nextRequest = this.fileRequests.remove(0);

                nextRequest.filenames = this.currentFileRequest.filenames;
                nextRequest.maxBlockSize = this.currentFileRequest.maxBlockSize;
                nextRequest.timeout = this.currentFileRequest.timeout;

                this.currentFileRequest = nextRequest;
                getFileInfo();
                return;
            }
        }
        reset();
    }

    private void reset() {
        // Stop listening for file data, if we were doing that
        this.supportProvider.removeInProgressRequests(this.fileDataReceiver);

        // Reset current request
        this.currentFileRequest = null;

        // Unset busy, otherwise the next download will never start
        synchronized (this.supportProvider) {
            this.isBusy = false;
        }
        // Try to download next file
        startDownload();
    }

    /** Abort the current download and drop the queue (used when a sync is cut short). */
    public void cancelAll() {
        this.handler.removeCallbacks(this.timeout);
        synchronized (supportProvider) {
            this.fileRequests.clear();
        }
        if (this.fileDataReceiver != null)
            this.supportProvider.removeInProgressRequests(this.fileDataReceiver);
        this.currentFileRequest = null;
        synchronized (this.supportProvider) {
            this.isBusy = false;
        }
    }

    public void dispose() {
        // Stop timeout from hitting, nothing else to really do
        this.handler.removeCallbacks(this.timeout);
    }
}
