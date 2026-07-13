"""
מעביר הודעות וידאו מקבוצה/ערוץ לבוט בנגלות של 30 כל 30 שניות.
דרישות: pip install telethon

איך להשיג API_ID ו-API_HASH:
  1. כנס ל-https://my.telegram.org
  2. התחבר עם מספר הטלפון שלך
  3. לחץ על "API development tools"
  4. צור אפליקציה — תקבל App api_id ו-App api_hash
"""

import asyncio
from telethon import TelegramClient
from telethon.tl.types import MessageMediaDocument, MessageMediaPhoto

# ── הגדרות — מלא את 3 השורות האלה ────────────────────────────
API_ID       = 0          # מספר מ-my.telegram.org
API_HASH     = ""         # מחרוזת מ-my.telegram.org
SOURCE_GROUP = ""         # לינק או username של הקבוצה/ערוץ המקור, למשל "@ZOVE8" או "https://t.me/ZOVE8"
BOT_USERNAME = ""         # הבוט שאליו לשלוח, למשל "@MyBot"
# ──────────────────────────────────────────────────────────────

BATCH_SIZE   = 30   # כמה הודעות בכל נגלה
WAIT_SECONDS = 30   # שניות המתנה בין נגלות


async def main():
    async with TelegramClient("zovex_session", API_ID, API_HASH) as client:
        print(f"מחובר. מושך הודעות מ-{SOURCE_GROUP}...")

        # משיכת כל ההודעות עם מדיה (וידאו/מסמך) מהקבוצה
        messages = []
        async for msg in client.iter_messages(SOURCE_GROUP):
            if msg.media and isinstance(msg.media, (MessageMediaDocument, MessageMediaPhoto)):
                messages.append(msg)

        messages.reverse()  # מהישן לחדש
        total = len(messages)
        print(f"נמצאו {total} הודעות מדיה. מעביר בנגלות של {BATCH_SIZE} כל {WAIT_SECONDS} שניות...")

        for batch_num, i in enumerate(range(0, total, BATCH_SIZE), start=1):
            batch = messages[i:i + BATCH_SIZE]
            print(f"\nנגלה {batch_num} — מעביר {len(batch)} הודעות...")

            for msg in batch:
                try:
                    await client.forward_messages(BOT_USERNAME, msg)
                    await asyncio.sleep(1)  # שנייה בין כל הודעה בתוך הנגלה
                except Exception as e:
                    print(f"  שגיאה בהודעה {msg.id}: {e}")

            remaining = total - (i + len(batch))
            if remaining > 0:
                print(f"נגלה {batch_num} הושלמה. נשארו {remaining}. מחכה {WAIT_SECONDS} שניות...")
                await asyncio.sleep(WAIT_SECONDS)

    print("\nהכל הועבר!")


if __name__ == "__main__":
    asyncio.run(main())
