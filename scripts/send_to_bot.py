"""
מעביר הודעות וידאו מערוץ ציבורי לבוט — דרך טוקן הבוט בלבד.
דרישות: pip install requests

צריך רק 3 דברים:
  BOT_TOKEN   — הטוקן של הבוט שלך (מ-@BotFather)
  SOURCE      — username הערוץ המקור (למשל ZOVE8)
  DEST_CHAT   — chat_id שאליו לשלוח (מספר שלילי לקבוצה, או @username)
"""

import requests
import time

# ── הגדרות ────────────────────────────────────────────────────
BOT_TOKEN  = ""        # הטוקן מ-@BotFather, למשל: "123456:ABC-DEF..."
SOURCE     = "ZOVE8"   # username של הערוץ המקור (בלי @)
DEST_CHAT  = ""        # לאן לשלוח — chat_id של הבוט/קבוצה, למשל "@MyBot" או "-1001234567"

FROM_MSG_ID = 1        # מאיזה מזהה הודעה להתחיל (1 = מההתחלה)
TO_MSG_ID   = 2000     # עד איזה מזהה (שנה לפי הצורך)

BATCH_SIZE   = 30      # כמה הודעות בנגלה
WAIT_SECONDS = 30      # המתנה בין נגלות (שניות)
# ──────────────────────────────────────────────────────────────

BASE = f"https://api.telegram.org/bot{BOT_TOKEN}"


def forward(from_chat, msg_id, to_chat):
    res = requests.post(f"{BASE}/forwardMessage", json={
        "chat_id": to_chat,
        "from_chat_id": f"@{from_chat}",
        "message_id": msg_id
    })
    return res.json().get("ok", False)


def main():
    msg_ids = list(range(FROM_MSG_ID, TO_MSG_ID + 1))
    total = len(msg_ids)
    print(f"מעביר הודעות {FROM_MSG_ID}–{TO_MSG_ID} מ-@{SOURCE} ({total} הודעות)...")

    sent = 0
    skipped = 0

    for batch_num, i in enumerate(range(0, total, BATCH_SIZE), start=1):
        batch = msg_ids[i:i + BATCH_SIZE]
        print(f"\nנגלה {batch_num} — מעביר {len(batch)} הודעות...")

        for msg_id in batch:
            ok = forward(SOURCE, msg_id, DEST_CHAT)
            if ok:
                sent += 1
                print(f"  ✓ {msg_id}")
            else:
                skipped += 1
                print(f"  ✗ {msg_id} (לא נמצא / לא וידאו — ממשיך)")
            time.sleep(0.5)

        remaining = total - (i + len(batch))
        if remaining > 0:
            print(f"נגלה {batch_num} הושלמה. נשארו {remaining}. מחכה {WAIT_SECONDS} שניות...")
            time.sleep(WAIT_SECONDS)

    print(f"\nסיום! נשלחו: {sent} | דולגו: {skipped}")


if __name__ == "__main__":
    main()
