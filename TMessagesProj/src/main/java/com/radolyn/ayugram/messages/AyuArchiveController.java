/*
 * This is the source code of VortexGram for Android.
 *
 * Archived chats: keeps the history of groups and channels you left or were
 * removed from, so it stays readable after the dialog is gone from Telegram.
 */

package com.radolyn.ayugram.messages;

import android.text.TextUtils;

import com.radolyn.ayugram.AyuConfig;
import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.database.AyuArchiveData;
import com.radolyn.ayugram.database.dao.ArchivedDialogDao;
import com.radolyn.ayugram.database.dao.ArchivedMessageDao;
import com.radolyn.ayugram.database.entities.ArchivedDialog;
import com.radolyn.ayugram.database.entities.ArchivedMessage;
import com.radolyn.ayugram.proprietary.AyuMessageUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Snapshots a dialog's locally cached history into a private archive before
 * Telegram drops it, which is what happens as soon as you leave a group or a
 * channel removes you.
 *
 * Only messages the device already has can be captured: once you are no longer
 * a member the server will not serve the history again, so the snapshot is
 * taken at the moment of leaving, and - for chats you opt into - kept topped up
 * while you are still a member.
 */
public class AyuArchiveController {
    /** Upper bound on messages captured per dialog, to keep a snapshot bounded. */
    public static final int MAX_MESSAGES_PER_DIALOG = 5000;

    private static AyuArchiveController instance;

    private final ArchivedDialogDao dialogDao;
    private final ArchivedMessageDao messageDao;

    private AyuArchiveController() {
        dialogDao = AyuArchiveData.getArchivedDialogDao();
        messageDao = AyuArchiveData.getArchivedMessageDao();
    }

    public static AyuArchiveController getInstance() {
        if (instance == null) {
            instance = new AyuArchiveController();
        }
        return instance;
    }

    /**
     * Captures the dialog as it stands right now. Safe to call more than once:
     * messages already captured are kept, new ones are added.
     *
     * @param reason one of {@link ArchivedDialog}'s REASON_* constants
     */
    public void archiveDialog(int accountId, long dialogId, int reason) {
        if (!AyuConfig.archiveLeftChats) {
            return;
        }
        // Groups and channels only - a private chat's history is not lost this way.
        if (dialogId >= 0) {
            return;
        }
        try {
            // The messages must be read out before Telegram deletes them, so that
            // part is synchronous. Mapping them and copying media is slow enough to
            // stall the interface, so it is handed to a background thread.
            List<TLRPC.Message> cached = readCachedMessages(accountId,
                    UserConfig.getInstance(accountId).getClientUserId(), dialogId);
            registerDialog(accountId, dialogId, reason);
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    storeMessages(accountId, dialogId, cached);
                } catch (Exception e) {
                    FileLog.e("AyuArchiveController.storeMessages", e);
                }
            });
        } catch (Exception e) {
            FileLog.e("AyuArchiveController.archiveDialog", e);
        }
    }

    private void registerDialog(int accountId, long dialogId, int reason) {
        long userId = UserConfig.getInstance(accountId).getClientUserId();
        TLRPC.Chat chat = MessagesController.getInstance(accountId).getChat(-dialogId);

        ArchivedDialog archived = dialogDao.get(userId, dialogId);
        if (archived == null) {
            archived = new ArchivedDialog();
            archived.userId = userId;
            archived.dialogId = dialogId;
        }
        if (chat != null) {
            archived.title = chat.title;
            archived.username = ChatObject.getPublicUsername(chat);
            archived.isChannel = ChatObject.isChannel(chat) && !chat.megagroup;
            archived.isMegagroup = chat.megagroup;
        } else if (TextUtils.isEmpty(archived.title)) {
            archived.title = String.valueOf(-dialogId);
        }
        archived.archivedDate = (int) (System.currentTimeMillis() / 1000);
        archived.reason = reason;
        dialogDao.insert(archived);
    }

    /**
     * Maps the captured messages and copies any media already on disk into the
     * archive. Runs off the main thread.
     */
    private void storeMessages(int accountId, long dialogId, List<TLRPC.Message> messages) {
        long userId = UserConfig.getInstance(accountId).getClientUserId();
        if (messages.isEmpty()) {
            notifyChanged(accountId);
            return;
        }

        ArrayList<ArchivedMessage> batch = new ArrayList<>();
        for (TLRPC.Message msg : messages) {
            ArchivedMessage entity = toArchivedMessage(accountId, msg, dialogId);
            if (entity != null) {
                batch.add(entity);
            }
        }
        // IGNORE on conflict, so re-archiving never duplicates or overwrites.
        messageDao.insertAll(batch);
        dialogDao.updateMessagesCount(userId, dialogId, messageDao.count(userId, dialogId));

        FileLog.d("VortexGram: archived dialog " + dialogId + ", stored " + batch.size() + " messages");
        notifyChanged(accountId);
    }

    private void notifyChanged(int accountId) {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(accountId)
                .postNotificationName(AyuConstants.ARCHIVED_DIALOGS_CHANGED));
    }

    private ArchivedMessage toArchivedMessage(int accountId, TLRPC.Message msg, long dialogId) {
        try {
            AyuSavePreferences prefs = new AyuSavePreferences(msg, accountId);
            prefs.setDialogId(dialogId);

            ArchivedMessage entity = new ArchivedMessage();
            AyuMessageUtils.mapAny(prefs, entity);
            AyuMessageUtils.mapMediaAny(prefs, entity, AyuConfig.saveMedia);
            entity.dialogId = dialogId;
            entity.entityCreateDate = (int) (System.currentTimeMillis() / 1000);
            entity.authorName = resolveAuthorName(accountId, entity.fromId);

            // Do not record a path into Telegram's cache: that file is about to be
            // cleaned up, and a dead path is worse than knowing media is missing.
            if (!TextUtils.isEmpty(entity.mediaPath) && !new File(entity.mediaPath).exists()) {
                entity.mediaPath = null;
            }
            return entity;
        } catch (Exception e) {
            FileLog.e("AyuArchiveController.toArchivedMessage", e);
            return null;
        }
    }

    private String resolveAuthorName(int accountId, long fromId) {
        try {
            if (fromId > 0) {
                TLRPC.User user = MessagesController.getInstance(accountId).getUser(fromId);
                if (user != null) {
                    return TextUtils.isEmpty(user.last_name)
                            ? user.first_name
                            : user.first_name + " " + user.last_name;
                }
            } else if (fromId < 0) {
                TLRPC.Chat chat = MessagesController.getInstance(accountId).getChat(-fromId);
                if (chat != null) {
                    return chat.title;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private List<TLRPC.Message> readCachedMessages(int accountId, long userId, long dialogId) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        SQLiteCursor cursor = null;
        try {
            cursor = MessagesStorage.getInstance(accountId).getDatabase().queryFinalized(
                    String.format(
                            java.util.Locale.US,
                            "SELECT data FROM messages_v2 WHERE uid = %d ORDER BY mid DESC LIMIT %d",
                            dialogId, MAX_MESSAGES_PER_DIALOG));
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                try {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) {
                        message.readAttachPath(data, userId);
                        result.add(message);
                    }
                } catch (Exception e) {
                    FileLog.e("AyuArchiveController deserialize", e);
                } finally {
                    data.reuse();
                }
            }
        } catch (Exception e) {
            FileLog.e("AyuArchiveController.readCachedMessages", e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return result;
    }

    /** Stores a single message as it arrives, for chats being kept topped up. */
    public void onMessageReceived(int accountId, TLRPC.Message msg) {
        if (!AyuConfig.archiveLeftChats || msg == null) {
            return;
        }
        long dialogId = org.telegram.messenger.MessageObject.getDialogId(msg);
        if (dialogId >= 0) {
            return;
        }
        try {
            long userId = UserConfig.getInstance(accountId).getClientUserId();
            // Only chats already being archived are topped up, so this stays cheap.
            if (!dialogDao.exists(userId, dialogId)) {
                return;
            }
            ArchivedMessage entity = toArchivedMessage(accountId, msg, dialogId);
            if (entity != null) {
                messageDao.insert(entity);
            }
        } catch (Exception e) {
            FileLog.e("AyuArchiveController.onMessageReceived", e);
        }
    }

    public List<ArchivedDialog> getDialogs(int accountId) {
        try {
            return dialogDao.getAll(UserConfig.getInstance(accountId).getClientUserId());
        } catch (Exception e) {
            FileLog.e("AyuArchiveController.getDialogs", e);
            return new ArrayList<>();
        }
    }

    public int getDialogsCount(int accountId) {
        try {
            return dialogDao.count(UserConfig.getInstance(accountId).getClientUserId());
        } catch (Exception e) {
            return 0;
        }
    }

    public List<ArchivedMessage> getMessages(int accountId, long dialogId, int limit, int offset) {
        try {
            return messageDao.getPage(UserConfig.getInstance(accountId).getClientUserId(), dialogId, limit, offset);
        } catch (Exception e) {
            FileLog.e("AyuArchiveController.getMessages", e);
            return new ArrayList<>();
        }
    }

    public List<ArchivedMessage> getMedia(int accountId, long dialogId) {
        try {
            return messageDao.getMedia(UserConfig.getInstance(accountId).getClientUserId(), dialogId);
        } catch (Exception e) {
            FileLog.e("AyuArchiveController.getMedia", e);
            return new ArrayList<>();
        }
    }

    public void deleteDialog(int accountId, long dialogId) {
        try {
            long userId = UserConfig.getInstance(accountId).getClientUserId();
            for (String path : messageDao.getMediaPaths(userId, dialogId)) {
                if (TextUtils.isEmpty(path)) {
                    continue;
                }
                File f = new File(path);
                if (f.exists() && path.contains(AyuMessagesController.attachmentsSubfolder)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            messageDao.deleteDialog(userId, dialogId);
            dialogDao.delete(userId, dialogId);
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(accountId)
                    .postNotificationName(AyuConstants.ARCHIVED_DIALOGS_CHANGED));
        } catch (Exception e) {
            FileLog.e("AyuArchiveController.deleteDialog", e);
        }
    }

    public void clean() {
        AyuArchiveData.clean();
        instance = null;
    }
}
