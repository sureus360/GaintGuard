from pydantic import BaseModel
from typing import Optional

class UserBase(BaseModel):
    email: str

class UserCreate(UserBase):
    password: str

class UserResponse(UserBase):
    id: int
    is_active: bool

    class Config:
        from_attributes = True

class Token(BaseModel):
    access_token: str
    token_type: str

class TokenData(BaseModel):
    email: Optional[str] = None

class AppInfoBase(BaseModel):
    package_name: str
    app_name: str
    usage_minutes: Optional[int] = 0

class AppInfoCreate(AppInfoBase):
    pass

class AppInfoResponse(AppInfoBase):
    id: int
    device_id: int
    is_blocked: bool
    usage_minutes: int

    class Config:
        from_attributes = True

class AppLimitCreate(BaseModel):
    package_name: str
    daily_limit_minutes: int

class AppLimitResponse(AppLimitCreate):
    id: int
    device_id: int

    class Config:
        from_attributes = True

