/*
 * This is the source code of VortexGram for Android.
 *
 * Archived chats: keeps the history of groups and channels you left or were
 * removed from, so it stays readable after the dialog is gone from Telegram.
 */

package com.radolyn.ayugram.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.radolyn.ayugram.database.entities.ArchivedDialog;

import java.util.List;

@Dao
public interface ArchivedDialogDao {
    @Query("SELECT * FROM archiveddialog WHERE userId = :userId ORDER BY archivedDate DESC")
    List<ArchivedDialog> getAll(long userId);

    /** Every account's archived dialogs, used to prime the in-memory id set. */
    @Query("SELECT * FROM archiveddialog")
    List<ArchivedDialog> getAllIds();

    @Query("SELECT * FROM archiveddialog WHERE userId = :userId AND dialogId = :dialogId")
    ArchivedDialog get(long userId, long dialogId);

    @Query("SELECT EXISTS(SELECT 1 FROM archiveddialog WHERE userId = :userId AND dialogId = :dialogId)")
    boolean exists(long userId, long dialogId);

    @Query("SELECT COUNT(*) FROM archiveddialog WHERE userId = :userId")
    int count(long userId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ArchivedDialog dialog);

    @Query("UPDATE archiveddialog SET messagesCount = :messagesCount WHERE userId = :userId AND dialogId = :dialogId")
    void updateMessagesCount(long userId, long dialogId, int messagesCount);

    @Query("DELETE FROM archiveddialog WHERE userId = :userId AND dialogId = :dialogId")
    void delete(long userId, long dialogId);
}
