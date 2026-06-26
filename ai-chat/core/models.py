from typing import TypedDict


class UserInterest(TypedDict):
    tab_key: str
    tag_key: str


class UserInfo(TypedDict):
    """User profile, aligned with user.proto UserProfile."""
    nickname: str
    age: int            # 0 = unset
    gender: str         # mapped: Unknown / Male / Female
    height: int         # cm, 0 = unset
    bio: str
    occupation: str
    education: str
    location: str       # preferred city/region text
    birthday: str       # yyyy-MM-dd, "" = unset
    interests: list[UserInterest]
    city: str           # actual city name
    state_code: str     # US state abbrev (CA / NY ...)
    race: str           # Asian / Black / White / Latino / ... , "" = unset
    current_time: str   # computed fuzzy local time at user's location, "" if unresolved
