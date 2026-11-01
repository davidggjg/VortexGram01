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
import com.radolyn.ayugram.database.entities.ArchivedMessage;

import java.util.List;

@Dao
public interface ArchivedMessageDao {
    @Query("SELECT * FROM archivedmessage WHERE userId = :userId AND dialogId = :dialogId ORDER BY messageId DESC LIMIT :limit OFFSET :offset")
    List<ArchivedMessage> getPage(long userId, long dialogId, int limit, int offset);

    @Query("SELECT * FROM archivedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId >= :startId AND messageId <= :endId ORDER BY messageId LIMIT :limit")
    List<ArchivedMessage> getRange(long userId, long dialogId, int startId, int endId, int limit);

    @Query("SELECT * FROM archivedmessage WHERE userId = :userId AND dialogId = :dialogId AND documentType != 0 ORDER BY messageId DESC")
    List<ArchivedMessage> getMedia(long userId, long dialogId);

    @Query("SELECT * FROM archivedmessage WHERE userId = :userId AND dialogId = :dialogId AND groupedId = :groupedId ORDER BY messageId")
    List<ArchivedMessage> getGrouped(long userId, long dialogId, long groupedId);

    @Query("SELECT COUNT(*) FROM archivedmessage WHERE userId = :userId AND dialogId = :dialogId")
    int count(long userId, long dialogId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(ArchivedMessage message);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<ArchivedMessage> messages);

    @Query("SELECT mediaPath FROM archivedmessage WHERE userId = :userId AND dialogId = :dialogId AND mediaPath IS NOT NULL")
    List<String> getMediaPaths(long userId, long dialogId);

    @Query("DELETE FROM archivedmessage WHERE userId = :userId AND dialogId = :dialogId")
    void deleteDialog(long userId, long dialogId);
}
