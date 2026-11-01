/*
 * This is the source code of VortexGram for Android.
 *
 * Archived chats: keeps the history of groups and channels you left or were
 * removed from, so it stays readable after the dialog is gone from Telegram.
 */

package com.radolyn.ayugram.database;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import com.radolyn.ayugram.database.dao.ArchivedDialogDao;
import com.radolyn.ayugram.database.dao.ArchivedMessageDao;
import com.radolyn.ayugram.database.entities.ArchivedDialog;
import com.radolyn.ayugram.database.entities.ArchivedMessage;

/**
 * Deliberately a separate database file from {@link AyuDatabase}.
 *
 * AyuDatabase is built with fallbackToDestructiveMigration(), so bumping its
 * version drops every saved deleted/edited message. Keeping the archive in its
 * own database means adding these tables cannot wipe a user's existing history.
 */
@Database(entities = {
        ArchivedDialog.class,
        ArchivedMessage.class
}, version = 1, exportSchema = false)
public abstract class AyuArchiveDatabase extends RoomDatabase {
    public abstract ArchivedDialogDao archivedDialogDao();

    public abstract ArchivedMessageDao archivedMessageDao();
}
