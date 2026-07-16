/*
 * This is the source code of VortexGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.proprietary;

import android.text.TextUtils;
import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.database.entities.AyuMessageBase;
import com.radolyn.ayugram.database.entities.DeletedMessage;
import com.radolyn.ayugram.database.entities.EditedMessage;
import com.radolyn.ayugram.messages.AyuMessagesController;
import com.radolyn.ayugram.messages.AyuSavePreferences;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;

public class AyuMessageUtils {

    public static void map(AyuSavePreferences prefs, EditedMessage revision) {
        mapToBase(prefs, revision);
    }

    public static void mapMedia(AyuSavePreferences prefs, EditedMessage revision, boolean copyMedia) {
        mapMediaToBase(prefs, revision, copyMedia);
    }

    public static void map(AyuSavePreferences prefs, DeletedMessage deletedMessage) {
        mapToBase(prefs, deletedMessage);
    }

    public static void mapMedia(AyuSavePreferences prefs, DeletedMessage deletedMessage, boolean copyMedia) {
        mapMediaToBase(prefs, deletedMessage, copyMedia);
    }

    public static void map(EditedMessage editedMessage, TLRPC.Message msg, int currentAccount) {
        AyuHistoryHook.mapFromBase(editedMessage, msg, currentAccount);
    }

    public static void mapMedia(EditedMessage editedMessage, TLRPC.Message msg) {
        AyuHistoryHook.mapMediaFromBase(editedMessage, msg);
    }

    private static void mapToBase(AyuSavePreferences prefs, AyuMessageBase base) {
        base.userId = prefs.getUserId();
        base.dialogId = prefs.getDialogId();
        base.topicId = prefs.getTopicId();
        base.messageId = prefs.getMessageId();
        base.entityCreateDate = prefs.getRequestCatchTime();

        var msg = prefs.getMessage();
        if (msg == null) {
            return;
        }

        base.text = msg.message;
        base.flags = msg.flags;
        base.editDate = msg.edit_date;
        base.views = msg.views;
        base.groupedId = msg.grouped_id;
        base.date = msg.date;

        if (msg.peer_id instanceof TLRPC.TL_peerUser) {
            base.peerId = msg.peer_id.user_id;
        } else if (msg.peer_id instanceof TLRPC.TL_peerChat) {
            base.peerId = -msg.peer_id.chat_id;
        } else if (msg.peer_id instanceof TLRPC.TL_peerChannel) {
            base.peerId = -msg.peer_id.channel_id;
        }

        if (msg.from_id instanceof TLRPC.TL_peerUser) {
            base.fromId = msg.from_id.user_id;
        } else if (msg.from_id instanceof TLRPC.TL_peerChat) {
            base.fromId = -msg.from_id.chat_id;
        } else if (msg.from_id instanceof TLRPC.TL_peerChannel) {
            base.fromId = -msg.from_id.channel_id;
        }

        if (msg.fwd_from != null) {
            base.fwdFlags = msg.fwd_from.flags;
            base.fwdDate = msg.fwd_from.date;
            base.fwdPostAuthor = msg.fwd_from.post_author;
            base.fwdName = msg.fwd_from.from_name;
            if (msg.fwd_from.from_id instanceof TLRPC.TL_peerUser) {
                base.fwdFromId = msg.fwd_from.from_id.user_id;
            } else if (msg.fwd_from.from_id instanceof TLRPC.TL_peerChat) {
                base.fwdFromId = -msg.fwd_from.from_id.chat_id;
            } else if (msg.fwd_from.from_id instanceof TLRPC.TL_peerChannel) {
                base.fwdFromId = -msg.fwd_from.from_id.channel_id;
            }
        }

        if (msg.reply_to != null) {
            base.replyFlags = msg.reply_to.flags;
            base.replyMessageId = msg.reply_to.reply_to_msg_id;
            base.replyTopId = msg.reply_to.reply_to_top_id;
            base.replyForumTopic = msg.reply_to.forum_topic;
            if (msg.reply_to.reply_to_peer_id instanceof TLRPC.TL_peerUser) {
                base.replyPeerId = msg.reply_to.reply_to_peer_id.user_id;
            } else if (msg.reply_to.reply_to_peer_id instanceof TLRPC.TL_peerChat) {
                base.replyPeerId = -msg.reply_to.reply_to_peer_id.chat_id;
            } else if (msg.reply_to.reply_to_peer_id instanceof TLRPC.TL_peerChannel) {
                base.replyPeerId = -msg.reply_to.reply_to_peer_id.channel_id;
            }
        }

        if (msg.entities != null && !msg.entities.isEmpty()) {
            try {
                var calc = new SerializedData(true);
                calc.writeInt32(msg.entities.size());
                for (var entity : msg.entities) {
                    entity.serializeToStream(calc);
                }
                var buf = new SerializedData(calc.length());
                buf.writeInt32(msg.entities.size());
                for (var entity : msg.entities) {
                    entity.serializeToStream(buf);
                }
                base.textEntities = buf.toByteArray();
            } catch (Exception e) {
                FileLog.e("AyuMessageUtils.mapToBase entities", e);
            }
        }
    }

    private static void mapMediaToBase(AyuSavePreferences prefs, AyuMessageBase base, boolean copyMedia) {
        var msg = prefs.getMessage();
        if (msg == null || MessageObject.getMedia(msg) == null) {
            base.documentType = AyuConstants.DOCUMENT_TYPE_NONE;
            return;
        }

        var media = MessageObject.getMedia(msg);

        if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
            base.documentType = AyuConstants.DOCUMENT_TYPE_PHOTO;
            if (copyMedia) {
                var srcFile = FileLoader.getInstance(prefs.getAccountId()).getPathToMessage(msg);
                base.mediaPath = copyMediaFile(srcFile, generateFilename(prefs, "jpg"));
            }
        } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            var doc = media.document;
            boolean isSticker = MessageObject.isStickerMessage(msg) || MessageObject.isAnimatedStickerMessage(msg);
            base.documentType = isSticker ? AyuConstants.DOCUMENT_TYPE_STICKER : AyuConstants.DOCUMENT_TYPE_FILE;
            base.mimeType = doc.mime_type;

            try {
                var calc = new SerializedData(true);
                doc.serializeToStream(calc);
                var buf = new SerializedData(calc.length());
                doc.serializeToStream(buf);
                base.documentSerialized = buf.toByteArray();
            } catch (Exception e) {
                FileLog.e("AyuMessageUtils document serialize", e);
            }

            if (doc.attributes != null && !doc.attributes.isEmpty()) {
                try {
                    var calc = new SerializedData(true);
                    calc.writeInt32(doc.attributes.size());
                    for (var attr : doc.attributes) {
                        attr.serializeToStream(calc);
                    }
                    var buf = new SerializedData(calc.length());
                    buf.writeInt32(doc.attributes.size());
                    for (var attr : doc.attributes) {
                        attr.serializeToStream(buf);
                    }
                    base.documentAttributesSerialized = buf.toByteArray();
                } catch (Exception e) {
                    FileLog.e("AyuMessageUtils attrs serialize", e);
                }
            }

            if (copyMedia) {
                var srcFile = FileLoader.getInstance(prefs.getAccountId()).getPathToMessage(msg);
                var ext = getExtension(doc.mime_type);
                base.mediaPath = copyMediaFile(srcFile, generateFilename(prefs, ext));
            }
        } else {
            base.documentType = AyuConstants.DOCUMENT_TYPE_NONE;
        }
    }

    private static String copyMediaFile(File src, String destName) {
        if (src == null || !src.exists()) {
            return src != null ? src.getAbsolutePath() : null;
        }
        try {
            var destFile = new File(AyuMessagesController.attachmentsPath, destName);
            if (!destFile.getParentFile().exists()) {
                destFile.getParentFile().mkdirs();
            }
            try (var in = new FileInputStream(src); var out = new FileOutputStream(destFile)) {
                var buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            return destFile.getAbsolutePath();
        } catch (Exception e) {
            FileLog.e("AyuMessageUtils.copyMediaFile", e);
            return src.getAbsolutePath();
        }
    }

    private static String generateFilename(AyuSavePreferences prefs, String ext) {
        return prefs.getDialogId() + "_" + prefs.getMessageId() + "_" + System.currentTimeMillis() + "." + ext;
    }

    private static String getExtension(String mimeType) {
        if (TextUtils.isEmpty(mimeType)) return "bin";
        switch (mimeType) {
            case "image/jpeg": return "jpg";
            case "image/png": return "png";
            case "image/gif": return "gif";
            case "image/webp": return "webp";
            case "video/mp4": return "mp4";
            case "audio/ogg": return "ogg";
            case "audio/mpeg": return "mp3";
            default: {
                var slash = mimeType.lastIndexOf('/');
                return slash >= 0 ? mimeType.substring(slash + 1) : "bin";
            }
        }
    }
}
