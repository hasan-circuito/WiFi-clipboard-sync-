"""
History manager for WiFi Clipboard Sync.
Stores recent clipboard items in memory and persists to a local JSON file.
"""

import json
import os
import time
import uuid
import threading
from typing import List, Dict, Optional

MAX_HISTORY_ITEMS = 50

class HistoryManager:
    def __init__(self, storage_path: Optional[str] = None):
        self.lock = threading.Lock()
        if storage_path is None:
            # Default to local app directory
            app_dir = os.path.dirname(os.path.abspath(__file__))
            self.storage_path = os.path.join(app_dir, "clipboard_history.json")
        else:
            self.storage_path = storage_path
            
        self.items: List[Dict] = []
        self._load()

    def _load(self):
        with self.lock:
            if os.path.exists(self.storage_path):
                try:
                    with open(self.storage_path, "r", encoding="utf-8") as f:
                        data = json.load(f)
                        if isinstance(data, list):
                            self.items = data[:MAX_HISTORY_ITEMS]
                except Exception:
                    self.items = []

    def _save(self):
        try:
            temp_path = self.storage_path + ".tmp"
            with open(temp_path, "w", encoding="utf-8") as f:
                json.dump(self.items[:MAX_HISTORY_ITEMS], f, ensure_ascii=False, indent=2)
            if os.path.exists(self.storage_path):
                os.replace(temp_path, self.storage_path)
            else:
                os.rename(temp_path, self.storage_path)
        except Exception:
            pass

    def add_entry(self, text: str, source: str = "pc") -> Dict:
        """
        Adds a new entry to the history.
        source is either 'pc' or 'phone'.
        """
        if not text:
            return {}

        with self.lock:
            # Remove existing identical entry if it's the most recent to avoid duplicates
            if self.items and self.items[0].get("text") == text:
                return self.items[0]

            preview = text.strip().replace("\r", " ").replace("\n", " ")
            if not preview:
                preview = f"[Whitespace: {len(text)} chars]"
            elif len(preview) > 90:
                preview = preview[:87] + "..."

            entry = {
                "id": str(uuid.uuid4())[:8],
                "text": text,
                "preview": preview,
                "source": source,
                "timestamp": time.time(),
                "time_str": time.strftime("%H:%M:%S"),
                "char_count": len(text)
            }
            self.items.insert(0, entry)
            if len(self.items) > MAX_HISTORY_ITEMS:
                self.items = self.items[:MAX_HISTORY_ITEMS]
            self._save()
            return entry

    def get_entries(self) -> List[Dict]:
        with self.lock:
            return list(self.items)

    def clear(self):
        with self.lock:
            self.items.clear()
            self._save()

    def remove_entry(self, entry_id: str) -> bool:
        with self.lock:
            before_len = len(self.items)
            self.items = [item for item in self.items if item.get("id") != entry_id]
            if len(self.items) != before_len:
                self._save()
                return True
            return False
