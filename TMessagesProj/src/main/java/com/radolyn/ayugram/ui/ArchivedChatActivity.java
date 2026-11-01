/*
 * This is the source code of VortexGram for Android.
 *
 * Archived chats: reads back the kept history of one group or channel,
 * including any media that was on the device when it was archived.
 */

package com.radolyn.ayugram.ui;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.database.entities.ArchivedMessage;
import com.radolyn.ayugram.messages.AyuArchiveController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class ArchivedChatActivity extends BaseFragment {

    private static final int MENU_SHARE_ALL = 1;

    private static final int VIEW_TYPE_MESSAGE = 0;
    private static final int VIEW_TYPE_EMPTY = 1;

    private final long dialogId;
    private final String chatTitle;

    private final List<ArchivedMessage> messages = new ArrayList<>();

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    public ArchivedChatActivity(long dialogId, String chatTitle) {
        this.dialogId = dialogId;
        this.chatTitle = chatTitle;
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        loadMessages();
        return true;
    }

    private void loadMessages() {
        messages.clear();
        // Stored newest first; read oldest first, the way a chat reads.
        List<ArchivedMessage> page = AyuArchiveController.getInstance()
                .getMessages(currentAccount, dialogId, AyuArchiveController.MAX_MESSAGES_PER_DIALOG, 0);
        messages.addAll(page);
        Collections.reverse(messages);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(TextUtils.isEmpty(chatTitle)
                ? LocaleController.getString(R.string.ArchivedChats)
                : chatTitle);
        actionBar.setSubtitle(LocaleController.formatString(
                "ArchivedChatMessagesCount", R.string.ArchivedChatMessagesCount, messages.size()));
        actionBar.setAllowOverlayTitle(false);
        actionBar.setOccupyStatusBar(!AndroidUtilities.isTablet());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SHARE_ALL) {
                    shareTranscript();
                }
            }
        });
        ActionBarMenuItem item = actionBar.createMenu().addItem(MENU_SHARE_ALL, R.drawable.msg_shareout);
        item.setContentDescription(LocaleController.getString(R.string.ShareFile));

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(listAdapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= messages.size()) {
                return;
            }
            openMedia(messages.get(position));
        });

        return fragmentView;
    }

    /** Opens the kept copy of a message's media in whatever app handles it. */
    private void openMedia(ArchivedMessage message) {
        if (TextUtils.isEmpty(message.mediaPath)) {
            if (message.documentType != AyuConstants.DOCUMENT_TYPE_NONE) {
                BulletinFactory.of(this)
                        .createErrorBulletin(LocaleController.getString(R.string.ArchivedChatMediaMissing))
                        .show();
            }
            return;
        }
        File file = new File(message.mediaPath);
        if (!file.exists()) {
            BulletinFactory.of(this)
                    .createErrorBulletin(LocaleController.getString(R.string.ArchivedChatMediaMissing))
                    .show();
            return;
        }
        if (getParentActivity() == null) {
            return;
        }
        AndroidUtilities.openForView(file, file.getName(),
                TextUtils.isEmpty(message.mimeType) ? "*/*" : message.mimeType,
                getParentActivity(), null);
    }

    /** Hands the whole kept history to another app as a plain text file. */
    private void shareTranscript() {
        if (getParentActivity() == null || messages.isEmpty()) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(TextUtils.isEmpty(chatTitle) ? String.valueOf(-dialogId) : chatTitle).append('\n');
            for (ArchivedMessage message : messages) {
                sb.append('[').append(formatDate(message.date)).append("] ");
                if (!TextUtils.isEmpty(message.authorName)) {
                    sb.append(message.authorName).append(": ");
                }
                if (!TextUtils.isEmpty(message.text)) {
                    sb.append(message.text);
                }
                if (!TextUtils.isEmpty(message.mediaPath)) {
                    sb.append(" <").append(new File(message.mediaPath).getName()).append('>');
                }
                sb.append('\n');
            }

            File out = new File(AndroidUtilities.getCacheDir(), "archive-" + (-dialogId) + ".txt");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(sb.toString().getBytes("UTF-8"));
            }

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(getParentActivity(),
                    ApplicationLoader.getApplicationId() + ".provider", out));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivity(Intent.createChooser(intent,
                    LocaleController.getString(R.string.ShareFile)));
        } catch (Exception e) {
            FileLog.e("ArchivedChatActivity.shareTranscript", e);
        }
    }

    private String formatDate(int secondsSinceEpoch) {
        return LocaleController.getInstance().formatterYear.format(new Date(secondsSinceEpoch * 1000L));
    }

    private String mediaLabel(ArchivedMessage message) {
        if (message.documentType == AyuConstants.DOCUMENT_TYPE_NONE) {
            return null;
        }
        if (TextUtils.isEmpty(message.mediaPath)) {
            return LocaleController.getString(R.string.ArchivedChatMediaMissing);
        }
        return new File(message.mediaPath).getName()
                + "  ·  " + LocaleController.getString(R.string.ArchivedChatOpenMedia);
    }

    /** One archived message: who sent it, when, its text, and its media, if kept. */
    private class MessageCell extends LinearLayout {

        private final TextView authorView;
        private final TextView textView;
        private final TextView mediaView;

        MessageCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            int pad = AndroidUtilities.dp(16);
            setPadding(pad, AndroidUtilities.dp(10), pad, AndroidUtilities.dp(10));

            authorView = new TextView(context);
            authorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            authorView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            authorView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            authorView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            addView(authorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f));

            mediaView = new TextView(context);
            mediaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            mediaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            mediaView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            addView(mediaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f));
        }

        void bind(ArchivedMessage message) {
            String author = TextUtils.isEmpty(message.authorName)
                    ? String.valueOf(message.fromId)
                    : message.authorName;
            authorView.setText(author + "  ·  " + formatDate(message.date));

            if (TextUtils.isEmpty(message.text)) {
                textView.setVisibility(GONE);
            } else {
                textView.setVisibility(VISIBLE);
                textView.setText(message.text);
            }

            String media = mediaLabel(message);
            if (media == null) {
                mediaView.setVisibility(GONE);
            } else {
                mediaView.setVisibility(VISIBLE);
                mediaView.setText(media);
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return messages.isEmpty() ? 1 : messages.size();
        }

        @Override
        public int getItemViewType(int position) {
            return messages.isEmpty() ? VIEW_TYPE_EMPTY : VIEW_TYPE_MESSAGE;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_MESSAGE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_EMPTY) {
                view = new TextInfoPrivacyCell(context);
            } else {
                view = new MessageCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == VIEW_TYPE_EMPTY) {
                ((TextInfoPrivacyCell) holder.itemView)
                        .setText(LocaleController.getString(R.string.ArchivedChatEmptyMessages));
            } else {
                ((MessageCell) holder.itemView).bind(messages.get(position));
            }
        }
    }
}
