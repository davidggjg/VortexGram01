/*
 * This is the source code of VortexGram for Android.
 *
 * Archived chats: keeps the history of groups and channels you left or were
 * removed from, so it stays readable after the dialog is gone from Telegram.
 */

package com.radolyn.ayugram.database.entities;

import androidx.room.Entity;

@Entity(primaryKeys = {"userId", "dialogId"})
public class ArchivedDialog {
    public static final int REASON_LEFT = 0;
    public static final int REASON_REMOVED = 1;
    public static final int REASON_DELETED = 2;
    /** Kept on purpose while still a member. */
    public static final int REASON_SAVED = 3;

    public long userId;
    public long dialogId;

    public String title;
    public String username;

    public boolean isChannel;
    public boolean isMegagroup;

    /** When the dialog was archived, in seconds. */
    public int archivedDate;

    /** One of REASON_*. */
    public int reason;

    /** How many messages were captured when archiving. */
    public int messagesCount;
}
