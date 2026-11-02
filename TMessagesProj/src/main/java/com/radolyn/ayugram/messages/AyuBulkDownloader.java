/*
 * This is the source code of VortexGram for Android.
 *
 * Grabs a chat's photos and videos as fast as the server will serve them.
 *
 * Some groups remove you within seconds, so this is built for speed: nothing
 * blocks on a confirmation, the local cache is kept before a single request
 * goes out, and the media index is fetched in parallel rather than page by
 * page. Every page that comes back is queued for download and archived the
 * moment it arrives, so whatever the server does hand over is already kept
 * even if access is cut off mid-run.
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class AyuBulkDownloader {

    /** Hard cap on how many media messages one run will take. */
    public static final int MAX_MEDIA = 5000;
    private static final int PAGE_SIZE = 100;
    /** Index requests kept in flight at once. More than this invites a flood wait. */
    private static final int MAX_PARALLEL_PAGES = 16;
    /** How long to keep waiting for queued downloads before the final sweep. */
    private static final long WAIT_TIMEOUT_MS = 6L * 60 * 60 * 1000;

    private static volatile boolean running;

    public interface Listener {
        /** Media found so far; called as each page lands. */
        void onFound(int totalQueued);

        void onFailed(String reason);
    }

    public static boolean isRunning() {
        return running;
    }

    /**
     * Starts immediately: no scan-then-confirm round trip, because in a chat
     * that removes you after ten seconds that round trip is the whole budget.
     */
    public static void start(int accountId, long dialogId, Listener listener) {
        if (running) {
            return;
        }
        running = true;

        // Before any network call: keep whatever is already on the device. If
        // access is lost one second from now, this much is still saved.
        try {
            AyuArchiveController.getInstance()
                    .archiveDialog(accountId, dialogId, ArchivedDialog.REASON_SAVED);
        } catch (Exception e) {
            FileLog.e("AyuBulkDownloader.start archive", e);
        }

        final List<String> queuedFiles = Collections.synchronizedList(new ArrayList<>());
        final List<TLRPC.Message> allMessages = Collections.synchronizedList(new ArrayList<>());

        requestPage(accountId, dialogId, 0, 0, firstPage -> {
            if (firstPage == null) {
                running = false;
                if (listener != null) {
                    listener.onFailed(null);
                }
                return;
            }

            handlePage(accountId, dialogId, firstPage, queuedFiles, allMessages, listener);

            int total = firstPage instanceof TLRPC.TL_messages_channelMessages
                    ? ((TLRPC.TL_messages_channelMessages) firstPage).count
                    : (firstPage instanceof TLRPC.TL_messages_messagesSlice
                    ? ((TLRPC.TL_messages_messagesSlice) firstPage).count
                    : firstPage.messages.size());
            total = Math.min(total, MAX_MEDIA);

            int remainingPages = (total - firstPage.messages.size() + PAGE_SIZE - 1) / PAGE_SIZE;
            if (remainingPages <= 0) {
                finish(accountId, dialogId, queuedFiles, allMessages);
                return;
            }

            // The server reports the total up front, so the rest of the index can
            // be asked for all at once with add_offset instead of walking the
            // pages one after another.
            final AtomicInteger outstanding = new AtomicInteger(remainingPages);
            for (int page = 1; page <= remainingPages; page++) {
                final int addOffset = page * PAGE_SIZE;
                Runnable fire = () -> requestPage(accountId, dialogId, 0, addOffset, res -> {
                    if (res != null) {
                        handlePage(accountId, dialogId, res, queuedFiles, allMessages, listener);
                    }
                    if (outstanding.decrementAndGet() <= 0) {
                        finish(accountId, dialogId, queuedFiles, allMessages);
                    }
                });
                if (page <= MAX_PARALLEL_PAGES) {
                    fire.run();
                } else {
                    // Stagger the tail so a long history does not trip a flood wait.
                    Utilities.globalQueue.postRunnable(fire, 150L * (page - MAX_PARALLEL_PAGES));
                }
            }
        });
    }

    private interface PageCallback {
        void onPage(TLRPC.messages_Messages result);
    }

    private static void requestPage(int accountId, long dialogId, int offsetId, int addOffset,
                                    PageCallback callback) {
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = MessagesController.getInstance(accountId).getInputPeer(dialogId);
        req.q = "";
        req.filter = new TLRPC.TL_inputMessagesFilterPhotoVideo();
        req.limit = PAGE_SIZE;
        req.offset_id = offsetId;
        req.add_offset = addOffset;

        ConnectionsManager.getInstance(accountId).sendRequest(req, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                callback.onPage(null);
                return;
            }
            callback.onPage((TLRPC.messages_Messages) response);
        });
    }

    /** Queues a page's files and archives its metadata straight away. */
    private static void handlePage(int accountId, long dialogId, TLRPC.messages_Messages page,
                                   List<String> queuedFiles, List<TLRPC.Message> allMessages,
                                   Listener listener) {
        ArrayList<TLRPC.Message> pageMessages = new ArrayList<>();
        for (int a = 0; a < page.messages.size(); a++) {
            TLRPC.Message message = page.messages.get(a);
            message.dialog_id = dialogId;
            pageMessages.add(message);
        }
        if (pageMessages.isEmpty()) {
            return;
        }
        allMessages.addAll(pageMessages);

        for (TLRPC.Message message : pageMessages) {
            String fileName = queueDownload(accountId, message);
            if (fileName != null) {
                queuedFiles.add(fileName);
            }
        }

        // Archive the text and sender now. Media paths get filled in by the
        // final sweep, once the files have actually landed.
        try {
            AyuArchiveController.getInstance().archiveMessages(accountId, dialogId, pageMessages);
        } catch (Exception e) {
            FileLog.e("AyuBulkDownloader.handlePage archive", e);
        }

        if (listener != null) {
            listener.onFound(queuedFiles.size());
        }
    }

    /** @return the loader's name for the queued file, or null if there is nothing to fetch */
    private static String queueDownload(int accountId, TLRPC.Message message) {
        try {
            FileLoader fileLoader = FileLoader.getInstance(accountId);
            TLRPC.Document document = MessageObject.getDocument(message);
            if (document != null) {
                fileLoader.loadFile(document, message, FileLoader.PRIORITY_HIGH, 0);
                return FileLoader.getAttachFileName(document);
            }
            TLRPC.MessageMedia media = MessageObject.getMedia(message);
            TLRPC.Photo photo = media != null ? media.photo : null;
            if (photo == null) {
                return null;
            }
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, Integer.MAX_VALUE);
            if (size == null) {
                return null;
            }
            fileLoader.loadFile(ImageLocation.getForPhoto(size, photo), message,
                    "jpg", FileLoader.PRIORITY_HIGH, 0);
            return FileLoader.getAttachFileName(size);
        } catch (Exception e) {
            FileLog.e("AyuBulkDownloader.queueDownload", e);
            return null;
        }
    }

    /**
     * Waits for the download queue to drain, then archives the whole set again
     * so the entries point at the files that have since landed on disk.
     */
    private static void finish(int accountId, long dialogId, List<String> queuedFiles,
                               List<TLRPC.Message> allMessages) {
        final long startedAt = System.currentTimeMillis();
        Utilities.globalQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                boolean stillGoing = false;
                synchronized (queuedFiles) {
                    for (String fileName : queuedFiles) {
                        if (VortexTransferService.isDownloading(fileName)) {
                            stillGoing = true;
                            break;
                        }
                    }
                }
                if (stillGoing && System.currentTimeMillis() - startedAt < WAIT_TIMEOUT_MS) {
                    Utilities.globalQueue.postRunnable(this, 2000);
                    return;
                }
                try {
                    ArrayList<TLRPC.Message> snapshot;
                    synchronized (allMessages) {
                        snapshot = new ArrayList<>(allMessages);
                    }
                    AyuArchiveController.getInstance().archiveMessages(accountId, dialogId, snapshot);
                } catch (Exception e) {
                    FileLog.e("AyuBulkDownloader.finish", e);
                } finally {
                    running = false;
                }
            }
        }, 2000);
    }
}
