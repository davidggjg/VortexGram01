# 🎯 TASK: Port VortexGram custom features to newer Telegram base

You have access to two repositories:
- `davidggjg/VortexGram01` — old base (Telegram 12.8.1 + AyuGram + ExteraGram fork), branch `claude/ayugram-vortexgram-refactor-8bb9hi`
- `davidggjg/Telegram` — newer Telegram base (the migration target)

**Your job:** read the exact changes on branch `claude/ayugram-vortexgram-refactor-8bb9hi` in VortexGram01 (diff vs main), understand each one, and apply the equivalent logic to the newer codebase in `davidggjg/Telegram`. Do NOT copy-paste blindly — line numbers will differ; find the correct insertion points in the new code.

---

## CHANGE 1 — Batch forwarding (AyuForwarder.java)

File: `TMessagesProj/src/main/java/com/radolyn/ayugram/AyuForwarder.java`

Add at the top of the class (before existing methods):

```java
public static final int RATE_LIMIT_BATCH = 30;
public static final long RATE_LIMIT_DELAY_MS = 30_000L;

public static void batchForward(int currentAccount, ArrayList<MessageObject> messages, long peer,
        boolean forwardFromMyName, boolean hideCaption, boolean notify, int scheduleDate,
        MessageObject replyToTopMsg) {
    int total = messages.size();
    int totalBatches = (int) Math.ceil(total / (double) RATE_LIMIT_BATCH);
    Handler handler = new Handler(Looper.getMainLooper());

    String initMsg = totalBatches == 1
            ? "שולח " + total + " הודעות..."
            : "שולח " + total + " הודעות | " + totalBatches + " נגלות, 30 שניות בין כל נגלה";
    handler.post(() -> Toast.makeText(ApplicationLoader.applicationContext, initMsg, Toast.LENGTH_LONG).show());

    for (int i = 0; i < total; i += RATE_LIMIT_BATCH) {
        final int batchNum = (i / RATE_LIMIT_BATCH) + 1;
        final ArrayList<MessageObject> batch = new ArrayList<>(
                messages.subList(i, Math.min(i + RATE_LIMIT_BATCH, total)));
        final int sentAfter = Math.min(i + RATE_LIMIT_BATCH, total);
        final long delayMs = (long) (batchNum - 1) * RATE_LIMIT_DELAY_MS;

        handler.postDelayed(() -> {
            handler.post(() -> {
                String progress = "נגלה " + batchNum + "/" + totalBatches + " — שולח " + batch.size() + " הודעות";
                Toast.makeText(ApplicationLoader.applicationContext, progress, Toast.LENGTH_SHORT).show();
            });
            new Thread(() -> {
                try {
                    intelligentForward(currentAccount, batch, peer, forwardFromMyName,
                            hideCaption, notify, scheduleDate, replyToTopMsg);
                    String done = batchNum == totalBatches
                            ? "הכל נשלח ✓  " + total + "/" + total
                            : "נגלה " + batchNum + "/" + totalBatches + " הושלמה — " + sentAfter + "/" + total;
                    handler.post(() -> Toast.makeText(ApplicationLoader.applicationContext, done, Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    Log.e("VortexGram", "batchForward: נגלה " + batchNum + " נכשלה", e);
                }
            }).start();
        }, delayMs);
    }
}
```

Required imports: `android.os.Handler`, `android.os.Looper`, `android.widget.Toast`

---

## CHANGE 2 — Auto-route large forwards to batchForward (SendMessagesHelper.java)

File: `TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java`

Find the method `forwardMessages(...)` — specifically the section where it checks `AyuForwarder.isAyuForwardNeeded(...)` or similar ayu-forward logic. **Before** that existing ayu check, insert:

```java
if (messages.size() > AyuForwarder.RATE_LIMIT_BATCH) {
    AyuForwarder.batchForward(currentAccount, messages, peer, forwardFromMyName, hideCaption, notify, scheduleDate, replyToTopMsg);
    return 0;
}
```

The goal: when the user forwards more than 30 messages at once, batchForward handles it automatically with rate-limiting.

---

## CHANGE 3 — Remove 100-message selection cap (ChatActivity.java)

File: `TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java`

### 3a. limitReached flag
Find the `onMultiSelectionChanged` callback inside `startMultiselect()`. Inside `onSelectionChanged`, find the line that sets `limitReached` based on `selectedMessagesIds[0].size() + selectedMessagesIds[1].size() >= 100`. Replace it with:
```java
limitReached = false; // VortexGram: no selection limit
```

### 3b. Hard block in addToSelectedMessages
Find the method `addToSelectedMessages(...)`. Inside the `else` branch (when adding a new message, not deselecting), find and remove this entire block:
```java
if (selectedMessagesIds[0].size() + selectedMessagesIds[1].size() >= 100) {
    if (selectedMessagesCountTextView != null) {
        selectedMessagesCountTextView.performHapticFeedback(...);
        AndroidUtilities.shakeView(selectedMessagesCountTextView);
    }
    return;
}
```
Keep only the code after it that actually does `selectedMessagesIds[index].put(...)`.

---

## CHANGE 4 — Range selection on long-press (ChatActivity.java)

Find `onItemLongClickListener` in `ChatActivity`. It has a pattern like:
```java
if (!actionBar.isActionModeShowed() && ...) {
    result = createMenu(...);
} else {
    processRowSelect(view, outside, x, y);
}
```

Replace the entire `else` block with:

```java
} else {
    boolean outside = false;
    if (view instanceof ChatMessageCell) {
        outside = !((ChatMessageCell) view).isInsideBackground(x, y);
    }
    int totalSelected = selectedMessagesIds[0].size() + selectedMessagesIds[1].size();
    if (totalSelected > 0 && view instanceof ChatMessageCell) {
        MessageObject pressedMsg = ((ChatMessageCell) view).getMessageObject();
        if (pressedMsg != null && pressedMsg.getId() > 0) {
            java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
            for (int a = 1; a >= 0; a--) {
                for (int b = 0; b < selectedMessagesIds[a].size(); b++) {
                    int k = selectedMessagesIds[a].keyAt(b);
                    if (k > 0) ids.add(k);
                }
            }
            if (!ids.isEmpty()) {
                java.util.Collections.sort(ids);
                int rangeBegin = Math.min(ids.get(0), pressedMsg.getId());
                int rangeEnd = Math.max(ids.get(ids.size() - 1), pressedMsg.getId());
                for (int i = 0; i < messages.size(); i++) {
                    MessageObject msg = messages.get(i);
                    int msgId = msg.getId();
                    if (msgId >= rangeBegin && msgId <= rangeEnd
                            && selectedMessagesIds[0].indexOfKey(msgId) < 0
                            && selectedMessagesIds[1].indexOfKey(msgId) < 0) {
                        addToSelectedMessages(msg, false);
                    }
                }
                updateActionModeTitle();
                updateVisibleRows();
            } else {
                processRowSelect(view, outside, x, y);
            }
        } else {
            processRowSelect(view, outside, x, y);
        }
    } else {
        processRowSelect(view, outside, x, y);
    }
}
```

**Effect:** while in selection mode, long-pressing a message selects the entire range between the current selection and the pressed message (like Shift+Click on desktop).

---

## CHANGE 5 — "Export Their Messages" menu item (ChatActivity.java)

### 5a. Add constant
Near the other `private final static int` menu ID constants, add:
```java
private final static int export_messages = 63;
```
If 63 conflicts with an existing ID, pick any unused number.

### 5b. Add menu item in createView
In `createView(Context context)`, find where `headerItem.lazilyAddSubItem(...)` items are added. Near `menu.setVisibility(...)`, add:
```java
if (headerItem != null && currentUser != null && !UserObject.isUserSelf(currentUser)) {
    headerItem.lazilyAddSubItem(export_messages, R.drawable.msg_saved, "Export Their Messages");
}
```

### 5c. Add click handler
In the menu click handler (where `id == translate`, `id == search` etc. are handled), add:
```java
} else if (id == export_messages) {
    exportOtherPartyMessages();
```

### 5d. Add the export method
Add this method to ChatActivity (near `processRowSelect` or other utility methods):

```java
private void exportOtherPartyMessages() {
    new Thread(() -> {
        try {
            java.util.ArrayList<MessageObject> toExport = new java.util.ArrayList<>();
            for (int i = messages.size() - 1; i >= 0; i--) {
                MessageObject msg = messages.get(i);
                if (!msg.isOut() && !msg.isDateObject && msg.getId() > 0 && msg.contentType == 0) {
                    toExport.add(msg);
                }
            }
            if (toExport.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, "No messages to export", Toast.LENGTH_SHORT).show());
                return;
            }
            String chatName = currentUser != null
                    ? ContactsController.formatName(currentUser.first_name, currentUser.last_name)
                    : (currentChat != null ? currentChat.title : "chat");
            chatName = chatName.replaceAll("[^\\w\\-]", "_");
            String filename = "export_" + chatName + "_" + System.currentTimeMillis() + ".txt";
            java.io.File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (!downloadsDir.exists()) downloadsDir.mkdirs();
            java.io.File outFile = new java.io.File(downloadsDir, filename);
            StringBuilder sb = new StringBuilder();
            for (MessageObject msg : toExport) {
                String text = msg.messageOwner.message;
                if (!TextUtils.isEmpty(text)) {
                    sb.append(text).append("\n");
                }
            }
            java.io.FileWriter fw = new java.io.FileWriter(outFile);
            fw.write(sb.toString());
            fw.close();
            final int count = toExport.size();
            final String path = outFile.getAbsolutePath();
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext,
                    count + " messages exported to\n" + path, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext,
                    "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }).start();
}
```

---

## ⚠️ IMPORTANT NOTES

1. **`ayuNoforwards` vs `noforwards`** — In VortexGram01 the field is `ayuNoforwards`. Check what it's called in `davidggjg/Telegram` before referencing it in AyuForwarder.

2. **`intelligentForward()` signature** — Must exist in AyuForwarder in the new base. If the method signature differs, adjust the `batchForward` call accordingly.

3. **`AyuEasyUtils`** — Used inside `intelligentForward`. Must be present in the new codebase.

4. **`msg_saved` drawable** — Verify it exists: `find . -name "msg_saved.png"`. If missing, use `msg_download_small` or another suitable drawable.

5. **`SparseArrayWithTouch`** — In VortexGram01, `selectedMessagesIds` is `SparseArrayWithTouch<MessageObject>[]`. In the new base it might just be `SparseArray<MessageObject>[]` — adjust `keyAt()` usage accordingly (same API, just different class name).

6. **`messages` field** — In ChatActivity it's `public ArrayList<MessageObject> messages`. Verify the field name is the same in the new codebase.

7. **`msg.contentType == 0`** — Used to filter normal messages vs date separators / service messages. Verify this convention is the same in the new codebase.

8. **Do NOT touch** `Movie.js`, `ApiKey.js`, `Live.js` — unrelated website files, not Android.

9. **After each file change** — verify compilation. Use `javac` or Gradle if available.

10. **Branch:** create and work on branch `claude/vortexgram-features` in `davidggjg/Telegram`, then push.

---

## VERIFICATION CHECKLIST

After applying all changes, verify:
- [ ] Forwarding 31+ messages triggers batchForward (Toast appears with batch progress)
- [ ] Can select more than 100 messages without the count freezing at 100
- [ ] Long-pressing a 2nd message while in selection mode fills in the range between them
- [ ] 3-dot menu in a 1-on-1 chat shows "Export Their Messages"
- [ ] Export creates a .txt file in Downloads and shows a Toast with the path
- [ ] Project compiles without errors
