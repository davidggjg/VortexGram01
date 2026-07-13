"""
שולח קבצים לבוט בנגלות של 30 כל 30 שניות.
דרישות: pip install telethon
"""

import asyncio
import os
from pathlib import Path
from telethon import TelegramClient

# ── הגדרות ────────────────────────────────────────────────────
API_ID    = 0          # מ-my.telegram.org → App configuration
API_HASH  = ""         # מ-my.telegram.org → App configuration
BOT_USERNAME = "@YourBotUsername"  # שם הבוט שלך

FILES_DIR    = "./videos"   # תיקיית הקבצים לשליחה
BATCH_SIZE   = 30           # כמה קבצים בכל נגלה
WAIT_SECONDS = 30           # המתנה בין נגלות (שניות)

# סיומות נתמכות — שנה לפי הצורך
EXTENSIONS = {".mp4", ".mkv", ".avi", ".mov", ".ts", ".m2ts"}
# ──────────────────────────────────────────────────────────────


async def main():
    files = sorted(
        p for p in Path(FILES_DIR).iterdir()
        if p.is_file() and p.suffix.lower() in EXTENSIONS
    )

    if not files:
        print(f"לא נמצאו קבצים ב-{FILES_DIR}")
        return

    print(f"נמצאו {len(files)} קבצים. נשלחים בנגלות של {BATCH_SIZE} כל {WAIT_SECONDS} שניות.")

    async with TelegramClient("zovex_session", API_ID, API_HASH) as client:
        for batch_num, i in enumerate(range(0, len(files), BATCH_SIZE), start=1):
            batch = files[i:i + BATCH_SIZE]
            print(f"\nנגלה {batch_num} — שולח {len(batch)} קבצים...")

            for file in batch:
                try:
                    print(f"  שולח: {file.name}")
                    await client.send_file(BOT_USERNAME, str(file), caption=file.name)
                    await asyncio.sleep(1)  # השהיה קטנה בין כל קובץ בתוך הנגלה
                except Exception as e:
                    print(f"  שגיאה בקובץ {file.name}: {e}")

            remaining = len(files) - (i + len(batch))
            if remaining > 0:
                print(f"נגלה {batch_num} הושלמה. נשארו {remaining} קבצים. מחכה {WAIT_SECONDS} שניות...")
                await asyncio.sleep(WAIT_SECONDS)

    print("\nהכל נשלח!")


if __name__ == "__main__":
    asyncio.run(main())
