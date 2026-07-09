/*
 * This is the source code of VortexGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.proprietary;

import android.util.Pair;
import android.util.SparseArray;
import android.text.TextUtils;
import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.database.entities.AyuMessageBase;
import com.radolyn.ayugram.messages.AyuMessagesController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public class AyuHistoryHook {

    public static Pair<Integer, Integer> getMinAndMaxIds(ArrayList<TLRPC.Message> messArr) {
        if (messArr.isEmpty()) {
            return Pair.create(0, 0);
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (TLRPC.Message msg : messArr) {
            if (msg.id < min) min = msg.id;
            if (msg.id > max) max = msg.id;
        }
        return Pair.create(min, max);
    }

    public static void doHook(int currentAccount, ArrayList<TLRPC.Message> messArr, SparseArray<MessageObject>[] messagesDict, int startId, int endId, long dialogId, int limit, long topicId, boolean isSecretChat) {
        if (isSecretChat) {
            return;
        }
        try {
            long userId = UserConfig.getInstance(currentAccount).getClientUserId();
            var deleted = AyuMessagesController.getInstance().getMessages(userId, dialogId, topicId, startId, endId, limit);
            if (deleted == null || deleted.isEmpty()) {
                return;
            }
            for (var full : deleted) {
                var dm = full.message;
                boolean found = false;
                for (var m : messArr) {
                    if (m.id == dm.messageId) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    continue;
                }
                var msg = new TLRPC.TL_message();
                mapFromBase(dm, msg, currentAccount);
                mapMediaFromBase(dm, msg);
                messArr.add(msg);
            }
        } catch (Exception e) {
            FileLog.e("AyuHistoryHook.doHook", e);
        }
    }

    static void mapFromBase(AyuMessageBase base, TLRPC.Message msg, int currentAccount) {
        msg.id = base.messageId;
        msg.message = base.text != null ? base.text : "";
        msg.flags = base.flags;
        msg.date = base.date;
        msg.edit_date = base.editDate;
        msg.views = base.views;
        msg.grouped_id = base.groupedId;
        msg.dialog_id = base.dialogId;

        if (base.fromId != 0) {
            msg.from_id = peerFromId(base.fromId);
            msg.flags |= 0x100; // FLAG_HAS_FROM_ID
        }
        if (base.peerId != 0) {
            msg.peer_id = peerFromId(base.peerId);
        } else {
            msg.peer_id = peerFromId(base.dialogId);
        }

        if (base.textEntities != null && base.textEntities.length > 0) {
            try {
                var sd = new SerializedData(base.textEntities);
                int count = sd.readInt32(false);
                if (count > 0 && count < 10000) {
                    msg.entities = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        var entity = TLRPC.MessageEntity.TLdeserialize(sd, sd.readInt32(false), false);
                        if (entity != null) {
                            msg.entities.add(entity);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (base.fwdFromId != 0 || !TextUtils.isEmpty(base.fwdName)) {
            var fwd = new TLRPC.TL_messageFwdHeader();
            fwd.flags = base.fwdFlags;
            fwd.date = base.fwdDate;
            fwd.post_author = base.fwdPostAuthor;
            if (base.fwdFromId != 0) {
                fwd.from_id = peerFromId(base.fwdFromId);
                fwd.flags |= 1;
            }
            if (!TextUtils.isEmpty(base.fwdName)) {
                fwd.from_name = base.fwdName;
                fwd.flags |= 32;
            }
            msg.fwd_from = fwd;
            msg.flags |= 4; // FLAG_FWD
        }

        if (base.replyMessageId != 0) {
            var reply = new TLRPC.TL_messageReplyHeader();
            reply.flags = base.replyFlags;
            reply.reply_to_msg_id = base.replyMessageId;
            reply.reply_to_top_id = base.replyTopId;
            reply.forum_topic = base.replyForumTopic;
            if (base.replyPeerId != 0) {
                reply.reply_to_peer_id = peerFromId(base.replyPeerId);
                reply.flags |= 1;
            }
            msg.reply_to = reply;
            msg.flags |= 8; // FLAG_REPLY
        }
    }

    static void mapMediaFromBase(AyuMessageBase base, TLRPC.Message msg) {
        if (base.documentType == AyuConstants.DOCUMENT_TYPE_NONE || TextUtils.isEmpty(base.mediaPath)) {
            return;
        }
        if (base.documentType == AyuConstants.DOCUMENT_TYPE_PHOTO) {
            var photo = new TLRPC.TL_photo();
            photo.sizes = new ArrayList<>();
            if (!TextUtils.isEmpty(base.mediaPath)) {
                var size = new TLRPC.TL_photoSizeEmpty();
                size.type = "s";
                photo.sizes.add(size);
            }
            var media = new TLRPC.TL_messageMediaPhoto();
            media.photo = photo;
            media.flags |= 1;
            msg.media = media;
            msg.flags |= 512; // FLAG_HAS_MEDIA
        } else if (base.documentType == AyuConstants.DOCUMENT_TYPE_STICKER || base.documentType == AyuConstants.DOCUMENT_TYPE_FILE) {
            if (base.documentSerialized != null && base.documentSerialized.length > 0) {
                try {
                    var sd = new SerializedData(base.documentSerialized);
                    var doc = TLRPC.Document.TLdeserialize(sd, sd.readInt32(false), false);
                    if (doc != null) {
                        var media = new TLRPC.TL_messageMediaDocument();
                        media.document = doc;
                        media.flags |= 1;
                        msg.media = media;
                        msg.flags |= 512;
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private static TLRPC.Peer peerFromId(long id) {
        if (id > 0) {
            var p = new TLRPC.TL_peerUser();
            p.user_id = id;
            return p;
        } else {
            var p = new TLRPC.TL_peerChat();
            p.chat_id = -id;
            return p;
        }
    }
}
