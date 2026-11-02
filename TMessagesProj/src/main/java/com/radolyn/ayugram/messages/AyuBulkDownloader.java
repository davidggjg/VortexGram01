/*
 * This is the source code of VortexGram for Android.
 *
 * Downloads every photo and video in a chat in one go, so the media is on the
 * device - and therefore keepable - before access to the chat can be lost.
 */

package com.radolyn.ayugram.messages;

import com.radolyn.ayugram.database.entities.ArchivedDialog;
import com.radolyn.ayugram.services.VortexTransferService;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

public class AyuBulkDownloader {

    /** Hard cap on how many media messages one run will collect. */
    public static final int MAX_MEDIA = 5000;
    private static final int PAGE_SIZE = 100;
    /** How long to keep waiting for the queued downloads before archiving. */
    private static final long WAIT_TIMEOUT_MS = 6L * 60 * 60 * 1000;

    private static volatile boolean running;

    public interface ScanCallback {
        /** @param totalSize sum of the media sizes in bytes, as reported by the server */
        void onScanned(List<TLRPC.Message> messages, long totalSize);

        void onError(String reason);
    }

    public interface ProgressCallback {
        void onProgress(int done, int total);

        void onFinished(int stored);
    }

    public static boolean isRunning() {
        return running;
    }

    /**
     * Asks the server for every photo and video in the chat. Paged, because a
     * single search call returns at most a hundred results.
     */
    public static void scan(int accountId, long dialogId, ScanCallback callback) {
        ArrayList<TLRPC.Message> collected = new ArrayList<>();
        scanPage(accountId, dialogId, 0, collected, callback);
    }

    private static void scanPage(int accountId, long dialogId, int offsetId,
                                 ArrayList<TLRPC.Message> collected, ScanCallback callback) {
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = MessagesController.getInstance(accountId).getInputPeer(dialogId);
        req.q = "";
        req.filter = new TLRPC.TL_inputMessagesFilterPhotoVideo();
        req.limit = PAGE_SIZE;
        req.offset_id = offsetId;

        ConnectionsManager.getInstance(accountId).sendRequest(req, (response, error) -> {
            if (error != null) {
                callback.onError(error.text);
                return;
            }
            if (!(response instanceof TLRPC.messages_Messages)) {
                callback.onError(null);
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            if (res.messages.isEmpty()) {
                finishScan(collected, callback);
                return;
            }

            int lastId = offsetId;
            for (int a = 0; a < res.messages.size(); a++) {
                TLRPC.Message message = res.messages.get(a);
                message.dialog_id = dialogId;
                collected.add(message);
                lastId = message.id;
            }

            if (collected.size() >= MAX_MEDIA || res.messages.size() < PAGE_SIZE) {
                finishScan(collected, callback);
                return;
            }
            scanPage(accountId, dialogId, lastId, collected, callback);
        });
    }

    private static void finishScan(ArrayList<TLRPC.Message> collected, ScanCallback callback) {
        long total = 0;
        for (TLRPC.Message message : collected) {
            total += estimateSize(message);
        }
        callback.onScanned(collected, total);
    }

    private static long estimateSize(TLRPC.Message message) {
        TLRPC.Document document = MessageObject.getDocument(message);
        if (document != null) {
            return document.size;
        }
        TLRPC.Photo photo = MessageObject.getMedia(message) != null
                ? MessageObject.getMedia(message).photo
                : null;
        if (photo != null) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, Integer.MAX_VALUE);
            if (size != null) {
                return size.size;
            }
        }
        return 0;
    }

    /**
     * Queues every collected file for download at high priority, then keeps the
     * chat's history - now with the media on disk - in the archive.
     */
    public static void download(int accountId, long dialogId, List<TLRPC.Message> messages,
                                ProgressCallback callback) {
        if (running) {
            return;
        }
        running = true;

        ArrayList<String> fileNames = new ArrayList<>();
        FileLoader fileLoader = FileLoader.getInstance(accountId);

        for (TLRPC.Message message : messages) {
            try {
                String fileName = FileLoader.getAttachFileName(MessageObject.getMedia(message));
                TLRPC.Document document = MessageObject.getDocument(message);
                if (document != null) {
                    fileLoader.loadFile(document, message, FileLoader.PRIORITY_NORMAL, 0);
                } else {
                    TLRPC.Photo photo = MessageObject.getMedia(message) != null
                            ? MessageObject.getMedia(message).photo
                            : null;
                    if (photo == null) {
                        continue;
                    }
                    TLRPC.PhotoSize size =
                            FileLoader.getClosestPhotoSizeWithSize(photo.sizes, Integer.MAX_VALUE);
                    if (size == null) {
                        continue;
                    }
                    fileName = FileLoader.getAttachFileName(size);
                    fileLoader.loadFile(ImageLocation.getForPhoto(size, photo), message,
                            "jpg", FileLoader.PRIORITY_NORMAL, 0);
                }
                if (fileName != null) {
                    fileNames.add(fileName);
                }
            } catch (Exception e) {
                FileLog.e("AyuBulkDownloader.download", e);
            }
        }

        // Show the chat in the archive straight away, so it is obvious the run
        // started even before any file has landed.
        AyuArchiveController.getInstance()
                .archiveDialog(accountId, dialogId, ArchivedDialog.REASON_LEFT);

        waitAndArchive(accountId, dialogId, messages, fileNames, callback);
    }

    /**
     * The media has to be on disk before it can be copied into the archive, so
     * this waits for the queue to drain and then takes the snapshot. Downloads
     * are driven by FileLoader, which reports no completion callback we can
     * attach to from here, hence the poll.
     */
    private static void waitAndArchive(int accountId, long dialogId, List<TLRPC.Message> messages,
                                       List<String> fileNames, ProgressCallback callback) {
        final long startedAt = System.currentTimeMillis();
        Utilities.globalQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                int remaining = 0;
                for (String fileName : fileNames) {
                    if (VortexTransferService.isDownloading(fileName)) {
                        remaining++;
                    }
                }
                int done = fileNames.size() - remaining;
                if (callback != null) {
                    callback.onProgress(done, fileNames.size());
                }
                if (remaining > 0 && System.currentTimeMillis() - startedAt < WAIT_TIMEOUT_MS) {
                    Utilities.globalQueue.postRunnable(this, 2000);
                    return;
                }
                try {
                    int stored = AyuArchiveController.getInstance()
                            .archiveMessages(accountId, dialogId, messages);
                    if (callback != null) {
                        callback.onFinished(stored);
                    }
                } catch (Exception e) {
                    FileLog.e("AyuBulkDownloader.waitAndArchive", e);
                } finally {
                    running = false;
                }
            }
        }, 2000);
    }
}
