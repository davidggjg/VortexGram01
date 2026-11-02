/*
 * This is the source code of VortexGram for Android.
 *
 * Archived chats: the list of groups and channels whose history was kept
 * after you left them or they removed you.
 */

package com.radolyn.ayugram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.database.entities.ArchivedDialog;
import com.radolyn.ayugram.messages.AyuArchiveController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

public class ArchivedChatsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_DIALOG = 1;
    private static final int VIEW_TYPE_INFO = 2;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private final List<ArchivedDialog> dialogs = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        getNotificationCenter().addObserver(this, AyuConstants.ARCHIVED_DIALOGS_CHANGED);
        reload();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, AyuConstants.ARCHIVED_DIALOGS_CHANGED);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == AyuConstants.ARCHIVED_DIALOGS_CHANGED) {
            reload();
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        }
    }

    private void reload() {
        dialogs.clear();
        dialogs.addAll(AyuArchiveController.getInstance().getDialogs(currentAccount));
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.VortexArchivedChats));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setOccupyStatusBar(!AndroidUtilities.isTablet());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(listAdapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            ArchivedDialog dialog = dialogAt(position);
            if (dialog != null) {
                presentFragment(new ArchivedChatActivity(dialog.dialogId, dialog.title));
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            ArchivedDialog dialog = dialogAt(position);
            if (dialog == null) {
                return false;
            }
            confirmDelete(dialog);
            return true;
        });

        return fragmentView;
    }

    private ArchivedDialog dialogAt(int position) {
        int index = position - 1; // the header occupies position 0
        if (index < 0 || index >= dialogs.size()) {
            return null;
        }
        return dialogs.get(index);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void confirmDelete(ArchivedDialog dialog) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.VortexArchivedChatDelete));
        builder.setMessage(LocaleController.formatString(
                "VortexArchivedChatDeleteConfirm", R.string.VortexArchivedChatDeleteConfirm,
                dialog.title == null ? "" : dialog.title));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, which) -> {
            AyuArchiveController.getInstance().deleteDialog(currentAccount, dialog.dialogId);
            reload();
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alertDialog = builder.create();
        showDialog(alertDialog);
        View button = alertDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private String describe(ArchivedDialog dialog) {
        String reason;
        if (dialog.reason == ArchivedDialog.REASON_SAVED) {
            reason = LocaleController.getString(R.string.VortexArchivedChatReasonSaved);
        } else if (dialog.reason == ArchivedDialog.REASON_REMOVED) {
            reason = LocaleController.getString(R.string.VortexArchivedChatReasonRemoved);
        } else if (dialog.reason == ArchivedDialog.REASON_DELETED) {
            reason = LocaleController.getString(R.string.VortexArchivedChatReasonDeleted);
        } else {
            reason = LocaleController.getString(R.string.VortexArchivedChatReasonLeft);
        }
        String count = LocaleController.formatString(
                "VortexArchivedChatMessagesCount", R.string.VortexArchivedChatMessagesCount, dialog.messagesCount);
        String date = LocaleController.getInstance().formatterYear.format(new java.util.Date(dialog.archivedDate * 1000L));
        return reason + " · " + date + " · " + count;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            // header + dialogs + trailing info
            return 1 + dialogs.size() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return VIEW_TYPE_HEADER;
            }
            if (position == getItemCount() - 1) {
                return VIEW_TYPE_INFO;
            }
            return VIEW_TYPE_DIALOG;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_DIALOG;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_HEADER: {
                    view = new HeaderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                }
                case VIEW_TYPE_INFO: {
                    view = new TextInfoPrivacyCell(context);
                    break;
                }
                default: {
                    view = new TextDetailSettingsCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                }
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_HEADER: {
                    ((HeaderCell) holder.itemView).setText(dialogs.isEmpty()
                            ? LocaleController.getString(R.string.VortexArchivedChatsEmpty)
                            : LocaleController.getString(R.string.VortexArchivedChatsHeader));
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(dialogs.isEmpty()
                            ? LocaleController.getString(R.string.VortexArchivedChatsEmptyInfo)
                            : LocaleController.getString(R.string.VortexArchivedChatsInfo));
                    break;
                }
                default: {
                    ArchivedDialog dialog = dialogAt(position);
                    if (dialog == null) {
                        break;
                    }
                    TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
                    cell.setTextAndValue(
                            dialog.title == null ? "" : dialog.title,
                            describe(dialog),
                            position < dialogs.size());
                    break;
                }
            }
        }
    }
}
