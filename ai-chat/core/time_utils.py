"""Location → timezone → fuzzy local-time helpers.

`current_local_time` resolves a user's timezone from city-center lat/lng (via
``tzfpy``), falling back to a US ``state_code`` map, then renders a *fuzzy*
local time (weekday + date + rounded "o'clock" phrasing) for the chat persona.
Ported from the Java ``formatTime`` reference, with weekday + date prepended.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from tzfpy import get_tz

# 美国州缩写 → IANA 时区。跨多时区的州取主导时区
# (FL/TX/TN 东部少部分为另一时区，这里取占比更大的那个)。
_STATE_TZ: dict[str, str] = {
    # Eastern
    "CT": "America/New_York", "DE": "America/New_York", "DC": "America/New_York",
    "FL": "America/New_York", "GA": "America/New_York", "IN": "America/New_York",
    "KY": "America/New_York", "ME": "America/New_York", "MD": "America/New_York",
    "MA": "America/New_York", "MI": "America/New_York", "NH": "America/New_York",
    "NJ": "America/New_York", "NY": "America/New_York", "NC": "America/New_York",
    "OH": "America/New_York", "PA": "America/New_York", "RI": "America/New_York",
    "SC": "America/New_York", "VT": "America/New_York", "VA": "America/New_York",
    "WV": "America/New_York",
    # Central
    "AL": "America/Chicago", "AR": "America/Chicago", "IL": "America/Chicago",
    "IA": "America/Chicago", "KS": "America/Chicago", "LA": "America/Chicago",
    "MN": "America/Chicago", "MS": "America/Chicago", "MO": "America/Chicago",
    "NE": "America/Chicago", "ND": "America/Chicago", "OK": "America/Chicago",
    "SD": "America/Chicago", "TN": "America/Chicago", "TX": "America/Chicago",
    "WI": "America/Chicago",
    # Mountain
    "CO": "America/Denver", "ID": "America/Denver", "MT": "America/Denver",
    "NM": "America/Denver", "UT": "America/Denver", "WY": "America/Denver",
    "AZ": "America/Phoenix",  # no DST
    # Pacific
    "CA": "America/Los_Angeles", "NV": "America/Los_Angeles",
    "OR": "America/Los_Angeles", "WA": "America/Los_Angeles",
    # Other
    "AK": "America/Anchorage", "HI": "Pacific/Honolulu",
}


def _resolve_zone(lat: float, lng: float, state_code: str) -> ZoneInfo | None:
    """lat/lng 优先（tzfpy），其次 US state_code，都不命中返回 None。"""
    # lat==lng==0 视为未填写：tzfpy 对 (0,0) 会返回 Etc/GMT，需要跳过
    if lat or lng:
        try:
            name = get_tz(lng, lat)  # 注意 tzfpy 参数顺序为 (lng, lat)
            if name and name != "Etc/GMT":
                return ZoneInfo(name)
        except Exception:
            pass
    name = _STATE_TZ.get(state_code.strip().upper())
    if name:
        return ZoneInfo(name)
    return None


def _format_fuzzy_time(now: datetime) -> str:
    """模糊本地时间：'Wednesday, 2026-06-04 3 o'clock PM in the afternoon'。"""
    hour = now.hour
    minute = now.minute

    adjusted_hour = hour % 12 or 12
    ampm = "PM" if hour >= 12 else "AM"

    if minute >= 30:
        next_adjusted = (hour + 1) % 12 or 12
        time_str = f"Almost {next_adjusted} o'clock"
    else:
        time_str = f"{adjusted_hour} o'clock"

    if 4 <= hour < 12:
        part_of_day = "in the morning"
    elif 12 <= hour < 18:
        part_of_day = "in the afternoon"
    elif 18 <= hour < 24:
        part_of_day = "in the evening"
    else:
        part_of_day = "past midnight"

    weekday = now.strftime("%A")
    date = now.strftime("%Y-%m-%d")
    return f"{weekday}, {date} {time_str} {ampm} {part_of_day}"


def current_local_time(lat: float, lng: float, state_code: str = "") -> str:
    """用户所在地的模糊本地时间；时区无法解析时返回空串。"""
    zone = _resolve_zone(lat, lng, state_code)
    if zone is None:
        return ""
    return _format_fuzzy_time(datetime.now(zone))
