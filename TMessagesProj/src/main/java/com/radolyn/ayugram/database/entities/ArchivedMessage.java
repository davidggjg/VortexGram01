/*
 * This is the source code of VortexGram for Android.
 *
 * Archived chats: keeps the history of groups and channels you left or were
 * removed from, so it stays readable after the dialog is gone from Telegram.
 */

package com.radolyn.ayugram.database.entities;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(indices = {@Index(value = {"userId", "dialogId", "messageId"}, unique = true)})
public class ArchivedMessage extends AyuMessageBase {
    @PrimaryKey(autoGenerate = true)
    public long fakeId;

    /** Message author's display name, resolved while the chat was still available. */
    public String authorName;
}
